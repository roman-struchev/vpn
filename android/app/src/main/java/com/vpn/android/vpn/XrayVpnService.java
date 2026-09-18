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
import com.vpn.android.api.model.DeviceDto;
import com.vpn.android.api.model.RoutingConfigResponse;
import com.vpn.android.api.model.SubscriptionLinksResponse;
import com.vpn.android.ui.MainActivity;
import com.vpn.android.vpn.state.ConnectionEvent;
import com.vpn.android.vpn.state.ConnectionState;
import com.vpn.android.vpn.state.ConnectionStateMachine;
import com.vpn.android.vpn.xray.VlessUri;
import com.vpn.android.vpn.xray.XrayConfigFactory;
import com.vpn.android.vpn.xray.XrayInvoker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import libXray.DialerController;
import com.vpn.android.diagnostics.DiagnosticsReporter;
import com.vpn.android.p2p.P2pRelayConnector;
import com.vpn.android.api.model.RelayInfo;

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
    // Liveness probe (see isTunnelAlive): a literal IP, so a broken DNS path can't
    // fail it on its own, and two strikes before acting so one slow probe on a
    // flaky mobile link doesn't cause a pointless reconnect.
    private static final String LIVENESS_PROBE_HOST = "1.1.1.1";
    private static final int LIVENESS_PROBE_PORT = 443;
    private static final int LIVENESS_PROBE_TIMEOUT_MS = 6000;
    private static final int LIVENESS_FAILURES_BEFORE_RECONNECT = 2;

    /**
     * How many relay peers to try before telling the user the network is
     * blocked. Each attempt is a full WebRTC negotiation, so more would mostly
     * mean a longer wait for the same answer.
     */
    private static final int MAX_RELAY_ATTEMPTS = 3;

    // The P2P hop, when one is in use, and the WebRTC factory behind it.
    private volatile P2pRelayConnector relayConnector;
    private org.webrtc.PeerConnectionFactory webRtcFactory;

    public static final String ACTION_CONNECT = "com.vpn.android.vpn.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vpn.android.vpn.action.DISCONNECT";
    /**
     * Re-runs the whole connect flow against the current settings, used when a
     * setting that's baked into the live tunnel changes mid-session (the pinned
     * region, which decides the node list, and the RU routing mode, which
     * decides both the Xray rules and the TUN's per-app filter). Without it
     * those settings silently applied only at the next manual connect.
     */
    public static final String ACTION_RECONNECT = "com.vpn.android.vpn.action.RECONNECT";

    public static final List<String> RUSSIAN_APP_PACKAGES = List.of(
            "ru.sberbankmobile",
            "com.idamob.tinkoff.android",
            "ru.alfabank.mobile.android",
            "ru.vtb24.mobilebanking",
            "ru.yandex.searchplugin",
            "ru.yandex.yandexmaps",
            "ru.yandex.taxi",
            "com.vkontakte.android",
            "ru.gosuslugi.gostop",
            "ru.nspk.mirpay",
            "com.ozon.app.android",
            "ru.wildberries.wildberries"
    );

    private final ConnectionStateMachine stateMachine = new ConnectionStateMachine();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable healthCheck = this::checkHealth;

    private ApiClient apiClient;
    private TokenStore tokenStore;
    private ParcelFileDescriptor tunInterface;
    private ReconnectBackoffPolicy backoffPolicy;
    private TransportFallbackPolicy transportFallbackPolicy;
    private List<VlessUri> nodes = new ArrayList<>();
    private final Map<String, Integer> grpcPortsByHost = new HashMap<>();
    private final Map<String, String> grpcServiceNamesByHost = new HashMap<>();
    private final Map<String, Long> nodeIdsByHost = new HashMap<>();
    private int currentNodeIndex = 0;
    private int consecutiveLivenessFailures = 0;
    private volatile boolean stopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        tokenStore = new TokenStore(this);
        apiClient = new ApiClient(tokenStore);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
            return START_NOT_STICKY;
        }
        if (ACTION_RECONNECT.equals(action)) {
            reconnect();
            return START_STICKY;
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

    /** See ACTION_RECONNECT. No-op unless a tunnel is currently up//coming up. */
    private void reconnect() {
        ConnectionState state = stateMachine.getState();
        if (state != ConnectionState.CONNECTED
                && state != ConnectionState.CONNECTING
                && state != ConnectionState.RECONNECTING) {
            return;
        }
        stopping = true;
        mainHandler.removeCallbacks(healthCheck);
        transition(ConnectionEvent.DISCONNECT_REQUESTED);
        worker.execute(() -> {
            try {
                XrayInvoker.stopXray();
            } catch (Exception e) {
                Log.w(TAG, "stopXray during reconnect failed (already stopped?)", e);
            }
            // The TUN is rebuilt rather than reused: the RU routing mode decides its
            // per-app allow/disallow list, which can only be set when establishing it.
            closeTun();
            stopping = false;
            transition(ConnectionEvent.CONNECT_REQUESTED);
            updateNotification();
            loadProfileAndConnect();
        });
    }

    private void loadProfileAndConnect() {
        try {
            // Must complete before fetching subscription links: a brand new account (or one whose
            // device was revoked) has zero devices, and the server returns an empty link list for
            // a user with no devices. Runs inline on this worker thread — queuing it via
            // registerOrTouchDevice() here ran it only AFTER this whole method returned, so the
            // links request always saw zero devices and failed with "No subscription links".
            registerOrTouchDeviceBlocking();
            RoutingConfigResponse policy = apiClient.getRoutingConfig(null, null);
            String preferredRegion = resolveConnectRegion();
            SubscriptionLinksResponse linksResp = apiClient.getSubscriptionLinks(preferredRegion);
            boolean regionFellBack = preferredRegion != null && Boolean.FALSE.equals(linksResp.requestedRegionAvailable);
            if (regionFellBack) {
                // Sane fallback per the region-picker spec: the server already
                // substituted the full node list, so the connect flow proceeds
                // normally below — this just explains why in the log/UI.
                Log.w(TAG, "Preferred region \"" + preferredRegion + "\" has no online node right now; falling back to all regions");
            }
            VpnStatusBus.regionFallback.postValue(regionFellBack);
            List<String> links = linksResp.links;
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
            // The dead end the user sees as "it just doesn't connect".
            DiagnosticsReporter.error("vpn", "PROFILE_LOAD_FAILED", "Failed to load the VPN profile", e);
            Log.e(TAG, "Failed to load VPN profile", e);
            transition(ConnectionEvent.FATAL_ERROR);
            updateNotification();
        }
    }

    /**
     * The user's manual region pick always wins. Otherwise, in RU-only routing
     * mode (see TokenStore#RUSSIAN_ROUTING_ONLY_RU), prefer an accessible
     * region whose name mentions Russia — that mode's whole point is reaching
     * RU-geo-restricted services, which requires a Russia-located exit node.
     * No Russian-region node existed anywhere as of this writing, so this
     * currently always falls through to null (server's own "auto" pick) in
     * practice — correctly wired, not faked, just inert until ops provisions
     * one; logged so that's visible rather than silently doing nothing.
     */
    private String resolveConnectRegion() {
        String manual = tokenStore.getSelectedRegion();
        if (manual != null) return manual;
        if (!TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(tokenStore.getRussianRoutingMode())) return null;
        try {
            for (com.vpn.android.api.model.RegionInfo r : apiClient.getRegions()) {
                if (r.accessible && r.region != null && r.region.toLowerCase(java.util.Locale.ROOT).contains("russia")) {
                    return r.region;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to look up a Russian region for RU-only routing mode; falling back to auto", e);
        }
        Log.w(TAG, "RU-only routing mode is active but no accessible Russian-region node exists right now");
        return null;
    }

    private static String normalizeFingerprint(String fingerprint) {
        return "edge".equals(fingerprint) ? "edge" : "firefox";
    }

    private void attemptTunnelStart() {
        if (stopping) return;
        VlessUri vless = nodes.get(currentNodeIndex % nodes.size());
        boolean useGrpc = transportFallbackPolicy.getCurrentTransport() == TransportFallbackPolicy.Transport.GRPC;
        long attemptStartUptimeMs = android.os.SystemClock.elapsedRealtime();
        try {
            ensureTunEstablished();
            int tunFd = tunInterface.getFd();

            XrayInvoker.registerDialerController(this);
            XrayInvoker.setDns(this, DNS_PROTECT_ENDPOINT);

            String assetDir = ensureGeoAssetsExtracted();
            String config = useGrpc
                    ? XrayConfigFactory.build(vless, backoffPolicy.getFingerprint(), tunFd, TUN_MTU,
                            "GRPC", grpcPortsByHost.get(vless.getHost()), grpcServiceNamesByHost.get(vless.getHost()),
                            assetDir)
                    : XrayConfigFactory.build(vless, backoffPolicy.getFingerprint(), tunFd, TUN_MTU,
                            "XHTTP", null, null, assetDir);
            XrayInvoker.runXray(config);

            backoffPolicy.onSuccess();
            transition(ConnectionEvent.TUNNEL_UP);
            VpnStatusBus.activeRegion.postValue(vless.getRegionLabel());
            updateNotification();
            mainHandler.postDelayed(healthCheck, 30_000);

            int connectTimeMs = (int) (android.os.SystemClock.elapsedRealtime() - attemptStartUptimeMs);
            Long connectedNodeId = nodeIdsByHost.get(vless.getHost());
            reportTelemetry(connectTimeMs, 0, false, connectedNodeId);
            registerOrTouchDevice();
        } catch (Exception e) {
            DiagnosticsReporter.error("vpn", "TUNNEL_START_FAILED",
                    "Tunnel start failed (transport=" + transportFallbackPolicy.getCurrentTransport() + ")", e,
                    java.util.Map.of("nodeIndex", String.valueOf(currentNodeIndex)));
            Log.w(TAG, "Tunnel start failed on node " + currentNodeIndex
                    + " (transport=" + transportFallbackPolicy.getCurrentTransport() + ")", e);
            handleFailure();
        }
    }

    /**
     * Last resort before declaring the network blocked: reach a node *through*
     * another user's device (docs §8.1).
     *
     * The relay dials the node for us and pipes opaque bytes; the tunnel is
     * still negotiated end-to-end with the node, so only the path changes —
     * which is why the config below keeps the node's own identity and merely
     * dials the local bridge (XrayConfigFactory's dialHost/dialPort).
     *
     * Tries a few peers rather than all of them: each attempt costs a
     * negotiation, and a user waiting to connect deserves an honest answer
     * sooner rather than a longer silence.
     */
    private boolean tryRelayedConnection() {
        if (stopping) return false;

        List<RelayInfo> relays;
        try {
            relays = apiClient.getP2pRelays();
        } catch (Exception e) {
            Log.w(TAG, "Could not look up P2P relays", e);
            return false;
        }
        if (relays.isEmpty()) {
            Log.i(TAG, "No P2P relay peers are available right now");
            return false;
        }

        VlessUri vless = nodes.get(currentNodeIndex % nodes.size());
        int attempts = 0;
        for (RelayInfo relay : relays) {
            if (stopping || attempts++ >= MAX_RELAY_ATTEMPTS) break;
            Log.i(TAG, "Trying to reach " + vless.getHost() + " through relay node " + relay.nodeId);
            stopRelayConnector();
            try {
                P2pRelayConnector connector = new P2pRelayConnector(
                        new ApiClientSignaling(apiClient),
                        peerConnectionFactory(),
                        relay.nodeId, vless.getHost(), vless.getPort());
                int localPort = connector.start();
                relayConnector = connector;

                ensureTunEstablished();
                XrayInvoker.registerDialerController(this);
                XrayInvoker.setDns(this, DNS_PROTECT_ENDPOINT);
                String config = XrayConfigFactory.build(
                        vless, backoffPolicy.getFingerprint(), tunInterface.getFd(), TUN_MTU,
                        "XHTTP", null, null, ensureGeoAssetsExtracted(), "127.0.0.1", localPort);
                XrayInvoker.runXray(config);

                backoffPolicy.onSuccess();
                transition(ConnectionEvent.TUNNEL_UP);
                // Said out loud: this path runs through a stranger's device and
                // is usually slower, so presenting it as an ordinary connection
                // would be misleading.
                VpnStatusBus.activeRegion.postValue(
                        getString(R.string.region_via_peer, vless.getRegionLabel()));
                updateNotification();
                mainHandler.postDelayed(healthCheck, 30_000);
                registerOrTouchDevice();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Relay node " + relay.nodeId + " did not work out", e);
                DiagnosticsReporter.error("p2p-relay", "RELAY_CONNECT_FAILED",
                        "Could not reach a node through a relay peer", e,
                        java.util.Map.of("relayNodeId", String.valueOf(relay.nodeId)));
                stopRelayConnector();
            }
        }
        return false;
    }

    /** Closes the P2P hop, if one is up. Safe when there is none. */
    private void stopRelayConnector() {
        P2pRelayConnector connector = relayConnector;
        relayConnector = null;
        if (connector != null) {
            connector.stop();
        }
    }

    /** Lazily built: WebRTC's factory costs native initialisation nobody should pay for a direct connection. */
    private synchronized org.webrtc.PeerConnectionFactory peerConnectionFactory() {
        if (webRtcFactory == null) {
            org.webrtc.PeerConnectionFactory.initialize(
                    org.webrtc.PeerConnectionFactory.InitializationOptions.builder(this)
                            .createInitializationOptions());
            webRtcFactory = org.webrtc.PeerConnectionFactory.builder().createPeerConnectionFactory();
        }
        return webRtcFactory;
    }

    /** Adapts ApiClient to what the connector needs, so it depends on four calls rather than the whole client. */
    private static final class ApiClientSignaling implements P2pRelayConnector.Signaling {
        private final ApiClient apiClient;

        ApiClientSignaling(ApiClient apiClient) {
            this.apiClient = apiClient;
        }

        @Override
        public void sendSignal(long relayNodeId, String sessionId, byte[] payload) throws Exception {
            apiClient.sendP2pSignal(relayNodeId, sessionId, payload);
        }

        @Override
        public byte[] pollSignal(String sessionId, long waitMs) throws Exception {
            return apiClient.pollP2pSignal(sessionId, waitMs);
        }

        @Override
        public void closeSession(String sessionId) {
            apiClient.closeP2pSession(sessionId);
        }

        @Override
        public void reportTraffic(String sessionId, long relayNodeId, long bytesRelayed) {
            apiClient.reportP2pSessionTraffic(sessionId, relayNodeId, bytesRelayed);
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
                    // Every node has failed on every transport and the probe
                    // blames the network itself — which is exactly what P2P
                    // relaying is for: another user's device can still reach a
                    // node we cannot, and will carry our bytes to it. Only if no
                    // relay works do we tell the user their operator is blocking.
                    if (tryRelayedConnection()) {
                        return;
                    }
                    reportTelemetry(0, backoffPolicy.getConsecutiveFailuresOnNode(), true, failedNodeId);
                    transition(ConnectionEvent.OPERATOR_BLOCK_DETECTED);
                    updateNotification();
                    return;
                }
            } else if (transportOutcome.transportChanged) {
                Log.i(TAG, "XHTTP exhausted across all nodes, falling back to gRPC+Reality (Phase 9)");
            }
        }
        reportTelemetry(0, backoffPolicy.getConsecutiveFailuresOnNode(), whitelistSuspected, failedNodeId);
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
     * which is keyed off nodeId. Called on both success (connectTimeMs measured,
     * failureCount=0) and failure (connectTimeMs=0, failureCount from the backoff
     * policy) — the dashboard needs the success reports as the denominator for a
     * real failure rate, not just an absolute failure count.
     */
    private void reportTelemetry(int connectTimeMs, int failureCount, boolean whitelistSuspected, Long nodeId) {
        String transport = transportFallbackPolicy.getCurrentTransport().name();
        worker.execute(() -> apiClient.submitTelemetry(
                nodeId, null, null, transport, connectTimeMs, failureCount, whitelistSuspected));
    }

    /**
     * Best-effort, on every successful connect: keeps this install counting as
     * a "recently active" device (server-side DEVICE_ACTIVE_WINDOW_DAYS) with
     * no manual "add device" step. Touches the locally-persisted device from
     * a prior run first; only registers a new one if that 404s (never
     * registered yet, or revoked elsewhere) — see TokenStore#getDeviceId.
     */
    private void registerOrTouchDevice() {
        worker.execute(this::registerOrTouchDeviceBlocking);
    }

    private void registerOrTouchDeviceBlocking() {
        try {
            long deviceId = tokenStore.getDeviceId();
            if (deviceId > 0 && apiClient.touchDevice(deviceId)) {
                return;
            }
            String name = Build.MANUFACTURER + " " + Build.MODEL;
            DeviceDto device = apiClient.addDevice(name, "ANDROID");
            tokenStore.saveDeviceId(device.id);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register/touch this device (best-effort)", e);
        }
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
                return;
            }
            // "xray is running" is not the same as "traffic actually flows": when the
            // server revokes this device's key (or the node stops accepting it), xray
            // keeps running happily while every connection fails, and the app kept
            // showing "Protected" with no working internet. Probing the tunnel itself
            // catches that — same intent as the desktop client's checkLiveness().
            if (!isTunnelAlive()) {
                consecutiveLivenessFailures++;
                // "Protected" with no working internet — invisible to the
                // user as a failure, so nobody would ever report it.
                DiagnosticsReporter.warn("vpn", "TUNNEL_PROBE_FAILED",
                        "Tunnel liveness probe failed while the tunnel was up");
                Log.w(TAG, "Health check: tunnel probe failed (" + consecutiveLivenessFailures + " in a row)");
                if (consecutiveLivenessFailures >= LIVENESS_FAILURES_BEFORE_RECONNECT) {
                    consecutiveLivenessFailures = 0;
                    handleFailure();
                    return;
                }
            } else {
                consecutiveLivenessFailures = 0;
            }
            mainHandler.postDelayed(healthCheck, 30_000);
        });
    }

    /**
     * TCP-connects to a well-known always-up endpoint through the tunnel (this
     * service's own sockets are not protected, so they go through the TUN like
     * any app's). One slow/blocked probe is not treated as a dead tunnel — see
     * LIVENESS_FAILURES_BEFORE_RECONNECT.
     */
    private boolean isTunnelAlive() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(LIVENESS_PROBE_HOST, LIVENESS_PROBE_PORT), LIVENESS_PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Copies the bundled {@code assets/geoip.dat} (same GeoIP database
     * desktop/agent ship next to their xray binary — see
     * desktop/resources/bin/<platform>/geoip.dat) out to a real file this
     * process can read, since Xray-core's Go file I/O cannot read out of the
     * APK's asset zip directly. Idempotent: skips the copy once the file
     * already exists with the expected size, so this is cheap to call on
     * every connect attempt. Returns the directory to hand to
     * XrayConfigFactory as XRAY_LOCATION_ASSET, or null if extraction fails
     * (best-effort — see class javadoc on XrayConfigFactory#build's
     * xrayAssetDir parameter for what breaks without it).
     */
    private String ensureGeoAssetsExtracted() {
        File dir = getFilesDir();
        File dest = new File(dir, "geoip.dat");
        try {
            long expectedLength = -1;
            try (android.content.res.AssetFileDescriptor afd = getAssets().openFd("geoip.dat")) {
                expectedLength = afd.getLength();
            } catch (Exception ignoredCompressed) {
                // openFd fails for assets stored compressed in the APK; fall through
                // to an always-copy below rather than trusting a stale dest file.
            }
            if (dest.exists() && (expectedLength < 0 || dest.length() == expectedLength)) {
                return dir.getAbsolutePath();
            }
            try (InputStream in = getAssets().open("geoip.dat");
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return dir.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract geoip.dat asset; geoip:private routing rule will fail", e);
            return null;
        }
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

        // App-based split tunneling (Android has no per-domain routing rule the
        // way the desktop client's system-proxy Xray config does, but a TUN
        // VpnService can exclude/restrict whole apps at the OS level, which is
        // the more natural fit here). RUSSIAN_APP_PACKAGES is the same list
        // either way; only which VpnService API call it feeds changes:
        //  - bypassRu: those apps are DISALLOWED from the tunnel (everything
        //    else — including all other apps — goes through the VPN).
        //  - onlyRu (reverse): those apps are the ONLY ones ALLOWED through
        //    the tunnel (everything else bypasses the VPN entirely). Needs an
        //    actual Russia-located exit node to be useful for RU-geo-blocked
        //    services — see resolveConnectRegion()'s doc.
        // addAllowedApplication and addDisallowedApplication are mutually
        // exclusive on one Builder (Android throws otherwise), so the custom
        // tokenStore.getDisallowedApps() overlay below only applies outside
        // onlyRu mode.
        String routingMode = tokenStore.getRussianRoutingMode();
        if (TokenStore.RUSSIAN_ROUTING_BYPASS.equals(routingMode)) {
            for (String pkg : RUSSIAN_APP_PACKAGES) {
                try {
                    builder.addDisallowedApplication(pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } else if (TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(routingMode)) {
            for (String pkg : RUSSIAN_APP_PACKAGES) {
                try {
                    builder.addAllowedApplication(pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        if (!TokenStore.RUSSIAN_ROUTING_ONLY_RU.equals(routingMode)) {
            Set<String> disallowed = tokenStore.getDisallowedApps();
            if (disallowed != null) {
                for (String pkg : disallowed) {
                    try {
                        builder.addDisallowedApplication(pkg);
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }
            }
        }

        tunInterface = builder.establish();
        if (tunInterface == null) {
            throw new IllegalStateException("VpnService.Builder#establish() returned null (permission revoked?)");
        }
    }


    private void disconnect() {
        stopping = true;
        mainHandler.removeCallbacks(healthCheck);
        stopRelayConnector();
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
        VpnStatusBus.regionFallback.postValue(false);
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
        stopRelayConnector();
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
