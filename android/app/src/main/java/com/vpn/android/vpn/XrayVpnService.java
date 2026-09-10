package com.vpn.android.vpn;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.vpn.android.R;
import com.vpn.android.VpnApp;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.api.model.RoutingConfigResponse;
import com.vpn.android.ui.MainActivity;
import com.vpn.android.vpn.state.ConnectionEvent;
import com.vpn.android.vpn.state.ConnectionState;
import com.vpn.android.vpn.state.ConnectionStateMachine;
import com.vpn.android.vpn.xray.VlessUri;
import com.vpn.android.vpn.xray.XrayConfigFactory;
import com.vpn.android.vpn.xray.XrayInvoker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import libXray.DialerController;

/**
 * Owns the whole VPN session lifecycle: fetching the node/policy list, building the
 * Xray config, driving {@link ConnectionStateMachine} + {@link ReconnectBackoffPolicy},
 * and the TUN file descriptor. Foreground (VpnService requires it) so Android doesn't
 * kill the tunnel in the background.
 */
public class XrayVpnService extends VpnService implements DialerController {

    private static final String TAG = "XrayVpnService";
    private static final int NOTIFICATION_ID = 1;
    private static final int TUN_MTU = 1500;
    private static final String DNS_PROTECT_ENDPOINT = "1.1.1.1:53";

    public static final String ACTION_CONNECT = "com.vpn.android.vpn.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vpn.android.vpn.action.DISCONNECT";

    private final ConnectionStateMachine stateMachine = new ConnectionStateMachine();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable healthCheck = this::checkHealth;

    private ApiClient apiClient;
    private ParcelFileDescriptor tunInterface;
    private ReconnectBackoffPolicy backoffPolicy;
    private TransportFallbackPolicy transportFallbackPolicy;
    private List<VlessUri> nodes = new ArrayList<>();
    private final Map<String, Integer> grpcPortsByHost = new HashMap<>();
    private final Map<String, String> grpcServiceNamesByHost = new HashMap<>();
    private final Map<String, Long> nodeIdsByHost = new HashMap<>();
    private int currentNodeIndex = 0;
    private volatile boolean stopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        apiClient = new ApiClient(new TokenStore(this));
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
            return START_NOT_STICKY;
        }
        connect();
        return START_STICKY;
    }

    private void connect() {
        if (stateMachine.getState() == ConnectionState.CONNECTING
                || stateMachine.getState() == ConnectionState.CONNECTED) {
            return;
        }
        stopping = false;
        transition(ConnectionEvent.CONNECT_REQUESTED);
        startForeground(NOTIFICATION_ID, buildNotification());
        worker.execute(this::loadProfileAndConnect);
    }

    private void loadProfileAndConnect() {
        try {
            RoutingConfigResponse policy = apiClient.getRoutingConfig(null, null);
            List<String> links = apiClient.getSubscriptionLinks().links;
            if (links == null || links.isEmpty()) {
                throw new IllegalStateException("No subscription links available for this account");
            }

            List<VlessUri> parsed = new ArrayList<>();
            for (String link : links) {
                try {
                    parsed.add(VlessUri.parse(link));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Skipping unparsable subscription link", e);
                }
            }
            if (parsed.isEmpty()) {
                throw new IllegalStateException("No usable subscription links after parsing");
            }

            this.nodes = parsed;
            this.currentNodeIndex = 0;
            this.backoffPolicy = new ReconnectBackoffPolicy(
                    policy.backoffInitialSec, policy.maxRetriesBeforeNodeSwitch, normalizeFingerprint(policy.fingerprint));

            grpcPortsByHost.clear();
            grpcServiceNamesByHost.clear();
            nodeIdsByHost.clear();
            if (policy.nodes != null) {
                for (RoutingConfigResponse.NodeInfo n : policy.nodes) {
                    if (n.grpcFallbackPort != null) grpcPortsByHost.put(n.publicIp, n.grpcFallbackPort);
                    if (n.grpcFallbackServiceName != null) grpcServiceNamesByHost.put(n.publicIp, n.grpcFallbackServiceName);
                    nodeIdsByHost.put(n.publicIp, n.id);
                }
            }
            boolean grpcAvailable = !grpcPortsByHost.isEmpty();
            TransportFallbackPolicy.Transport initialTransport = "GRPC".equalsIgnoreCase(policy.primaryTransport)
                    ? TransportFallbackPolicy.Transport.GRPC
                    : TransportFallbackPolicy.Transport.XHTTP;
            this.transportFallbackPolicy = new TransportFallbackPolicy(parsed.size(), grpcAvailable, initialTransport);

            attemptTunnelStart();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load VPN profile", e);
            transition(ConnectionEvent.FATAL_ERROR);
            updateNotification();
        }
    }

    private static String normalizeFingerprint(String fingerprint) {
        return "edge".equals(fingerprint) ? "edge" : "firefox";
    }

    private void attemptTunnelStart() {
        if (stopping) return;
        VlessUri vless = nodes.get(currentNodeIndex % nodes.size());
        boolean useGrpc = transportFallbackPolicy.getCurrentTransport() == TransportFallbackPolicy.Transport.GRPC;
        try {
            ensureTunEstablished();
            int tunFd = tunInterface.getFd();

            XrayInvoker.registerDialerController(this);
            XrayInvoker.setDns(this, DNS_PROTECT_ENDPOINT);

            String config = useGrpc
                    ? XrayConfigFactory.build(vless, backoffPolicy.getFingerprint(), tunFd, TUN_MTU,
                            "GRPC", grpcPortsByHost.get(vless.getHost()), grpcServiceNamesByHost.get(vless.getHost()))
                    : XrayConfigFactory.build(vless, backoffPolicy.getFingerprint(), tunFd, TUN_MTU);
            XrayInvoker.runXray(config);

            backoffPolicy.onSuccess();
            transition(ConnectionEvent.TUNNEL_UP);
            VpnStatusBus.activeRegion.postValue(vless.getRemark());
            updateNotification();
            mainHandler.postDelayed(healthCheck, 30_000);
        } catch (Exception e) {
            Log.w(TAG, "Tunnel start failed on node " + currentNodeIndex
                    + " (transport=" + transportFallbackPolicy.getCurrentTransport() + ")", e);
            handleFailure();
        }
    }

    private void handleFailure() {
        if (stopping) return;

        // Capture which node this failure is actually about before currentNodeIndex
        // potentially advances below — telemetry must be attributed to the node that
        // just failed, not to whichever node we're about to try next.
        Long failedNodeId = nodeIdsByHost.get(nodes.get(currentNodeIndex % nodes.size()).getHost());

        ReconnectBackoffPolicy.Decision decision = backoffPolicy.onFailure();
        boolean whitelistSuspected = false;
        if (decision.switchNode) {
            currentNodeIndex++;
            TransportFallbackPolicy.Outcome transportOutcome = transportFallbackPolicy.onNodeSwitch();
            if (transportOutcome.allTransportsExhausted) {
                CensorshipVerdict.Result verdict = new CensorshipProbeService().probe();
                whitelistSuspected = verdict == CensorshipVerdict.Result.OPERATOR_RESTRICTION;
                if (whitelistSuspected) {
                    reportTelemetry(decision, true, failedNodeId);
                    transition(ConnectionEvent.OPERATOR_BLOCK_DETECTED);
                    updateNotification();
                    return;
                }
            } else if (transportOutcome.transportChanged) {
                Log.i(TAG, "XHTTP exhausted across all nodes, falling back to gRPC+Reality (Phase 9)");
            }
        }
        reportTelemetry(decision, whitelistSuspected, failedNodeId);
        transition(ConnectionEvent.TUNNEL_DOWN);
        updateNotification();
        worker.execute(() -> {
            try {
                Thread.sleep(decision.delaySeconds * 1000L);
            } catch (InterruptedException ignored) {
                return;
            }
            if (!stopping) {
                attemptTunnelStart();
            }
        });
    }

    /**
     * Best-effort — feeds the admin degradation dashboard and
     * DynamicRoutingService's auto-quarantine (docs/ROADMAP_PROGRESS.md §3),
     * which is keyed off nodeId.
     */
    private void reportTelemetry(ReconnectBackoffPolicy.Decision decision, boolean whitelistSuspected, Long nodeId) {
        String transport = transportFallbackPolicy.getCurrentTransport().name();
        worker.execute(() -> apiClient.submitTelemetry(
                nodeId, null, null, transport, 0, backoffPolicy.getConsecutiveFailuresOnNode(), whitelistSuspected));
    }

    private void checkHealth() {
        if (stopping || stateMachine.getState() != ConnectionState.CONNECTED) {
            return;
        }
        worker.execute(() -> {
            boolean running = false;
            try {
                running = XrayInvoker.getXrayState();
            } catch (Exception ignored) {
                // treated as not running below
            }
            if (!running) {
                Log.w(TAG, "Health check: xray core is not running, reconnecting");
                handleFailure();
            } else {
                mainHandler.postDelayed(healthCheck, 30_000);
            }
        });
    }

    private void ensureTunEstablished() throws Exception {
        if (tunInterface != null) {
            return;
        }
        Builder builder = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                .addAddress("10.10.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }
        tunInterface = builder.establish();
        if (tunInterface == null) {
            throw new IllegalStateException("VpnService.Builder#establish() returned null (permission revoked?)");
        }
    }

    private void disconnect() {
        stopping = true;
        mainHandler.removeCallbacks(healthCheck);
        worker.execute(() -> {
            try {
                XrayInvoker.stopXray();
            } catch (Exception e) {
                Log.w(TAG, "stopXray failed (already stopped?)", e);
            }
            XrayInvoker.resetDns();
            closeTun();
        });
        transition(ConnectionEvent.DISCONNECT_REQUESTED);
        VpnStatusBus.activeRegion.postValue(null);
        stopForeground(true);
        stopSelf();
    }

    private void closeTun() {
        if (tunInterface != null) {
            try {
                tunInterface.close();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close TUN fd", e);
            }
            tunInterface = null;
        }
    }

    private void transition(ConnectionEvent event) {
        try {
            ConnectionState newState = stateMachine.dispatch(event);
            VpnStatusBus.state.postValue(newState);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Ignored invalid transition: " + event + " from " + stateMachine.getState());
        }
    }

    // --- DialerController: protects every Go-initiated socket from re-entering the tunnel ---
    @Override
    public boolean protectFd(long fd) {
        return protect((int) fd);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        mainHandler.removeCallbacks(healthCheck);
        closeTun();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        // User revoked VPN permission (or another VPN app took over) from system settings.
        disconnect();
        super.onRevoke();
    }

    private Notification buildNotification() {
        ConnectionState state = stateMachine.getState();
        String text = statusText(state);

        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingContent = PendingIntent.getActivity(
                this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent disconnectIntent = new Intent(this, XrayVpnService.class).setAction(ACTION_DISCONNECT);
        PendingIntent pendingDisconnect = PendingIntent.getService(
                this, 0, disconnectIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, VpnApp.VPN_STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pendingContent)
                .addAction(0, getString(R.string.notif_disconnect_action), pendingDisconnect)
                .build();
    }

    private void updateNotification() {
        mainHandler.post(() -> {
            // POST_NOTIFICATIONS may be denied on API 33+; the foreground service itself
            // keeps running either way, it just won't show a visible notification.
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
            }
        });
    }

    private String statusText(ConnectionState state) {
        switch (state) {
            case CONNECTED: return getString(R.string.state_connected);
            case CONNECTING: return getString(R.string.state_connecting);
            case RECONNECTING: return getString(R.string.state_reconnecting);
            case OPERATOR_BLOCKED: return getString(R.string.state_operator_blocked);
            case ERROR: return getString(R.string.state_error);
            default: return getString(R.string.state_disconnected);
        }
    }
}
