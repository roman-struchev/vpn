package com.vpn.android.p2p;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.vpn.android.R;
import com.vpn.android.VpnApp;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service hosting {@link P2pRelayAgent} for as long as relay mode
 * is TIMED or ALWAYS (docs/research/P2P_RELAY_FEASIBILITY.md §8.5) — a
 * foreground service + persistent notification is required on API 26+ for
 * any long-running background work like this, same constraint
 * XrayVpnService is already built around. Started by P2pRelaySettingsActivity
 * when the user turns relay mode on, and by BootReceiver on device restart
 * if the last-known mode was ALWAYS.
 */
public class P2pRelayService extends Service {

    private static final String TAG = "P2pRelayService";
    private static final int NOTIFICATION_ID = 2; // distinct from XrayVpnService's own NOTIFICATION_ID (1)

    public static final String EXTRA_RELAY_MODE = "relay_mode";
    public static final String EXTRA_RELAY_EXPIRES_AT = "relay_expires_at_epoch_ms";
    public static final String ACTION_STOP = "com.vpn.android.p2p.action.STOP";

    // Single-threaded, not Async's shared cached pool: onStartCommand calls
    // arrive serially on the main thread (that's the Android Service
    // contract), and agent's start/stop/updateRelayMode calls must preserve
    // that same serial order once moved to a background thread — otherwise
    // two overlapping calls (e.g. rapid mode-switch clicks) could race on
    // the plain `agent` field (check-then-act on "is it null yet") and end
    // up registering two agents, or one callback clobbering the other's
    // freshly-created instance.
    private final ExecutorService relayExecutor = Executors.newSingleThreadExecutor();
    private volatile P2pRelayAgent agent;

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Routed through relayExecutor, not called inline — otherwise this
            // could run on the main thread concurrently with an in-flight
            // start (see relayExecutor's field doc), observe `agent` still
            // null (the background start hasn't assigned it yet), skip
            // stopping anything, and then the start finishes and assigns
            // `agent` to a now-orphaned, never-stopped instance right as this
            // service is being torn down.
            relayExecutor.execute(this::stopRelay);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        String relayMode = intent != null ? intent.getStringExtra(EXTRA_RELAY_MODE) : TokenStore.P2P_RELAY_OFF;
        long relayExpiresAt = intent != null ? intent.getLongExtra(EXTRA_RELAY_EXPIRES_AT, 0L) : 0L;
        if (relayMode == null) relayMode = TokenStore.P2P_RELAY_OFF;

        TokenStore tokenStore = new TokenStore(this);
        tokenStore.saveP2pRelayState(relayMode, relayExpiresAt);

        if (TokenStore.P2P_RELAY_OFF.equals(relayMode)) {
            relayExecutor.execute(this::stopRelay); // see the ACTION_STOP branch's comment above — same reasoning
            return START_NOT_STICKY;
        }

        // agent.start()/updateRelayMode() do blocking network I/O (RegisterNode
        // is a *blocking* gRPC stub call — see P2pRelayAgent#registerNode) and
        // onStartCommand always runs on the main thread; calling either
        // directly here used to freeze the UI for the length of that round
        // trip on every relay-mode change, and risked an ANR on a slow
        // connection. Submitted to relayExecutor (single-threaded — see its
        // field doc) rather than run inline. The STICKY/NOT_STICKY return
        // value only needs to reflect the *intended* mode (already known
        // synchronously from the intent), not whether registration has
        // actually finished by the time this method returns — Android's
        // contract for that return value is purely "restart me if killed",
        // nothing about completion.
        final String finalRelayMode = relayMode;
        final long finalRelayExpiresAt = relayExpiresAt;
        relayExecutor.execute(() -> {
            try {
                if (agent == null) {
                    // start() auto-detects+persists the node's own region on
                    // first-ever call (see P2pRelayAgent#start) — nothing to
                    // pass in from here, including on BootReceiver's ALWAYS-
                    // mode reboot restart, which reuses whatever was already
                    // cached.
                    P2pRelayAgent newAgent = new P2pRelayAgent(this, tokenStore, new ApiClient(tokenStore), message ->
                            Log.w(TAG, "relay agent error: " + message));
                    newAgent.start(finalRelayMode, finalRelayExpiresAt);
                    agent = newAgent;
                } else {
                    agent.updateRelayMode(finalRelayMode, finalRelayExpiresAt);
                }
            } catch (Exception e) {
                // stop()/stopForeground()/stopSelf()/SharedPreferences#apply are
                // all documented safe to call off the main thread — no need to
                // bounce back via a Handler just to tear down after a failure.
                Log.e(TAG, "failed to start P2P relay agent", e);
                stopRelay();
            }
        });

        // ALWAYS mode must survive an app/process kill and a device reboot
        // (explicit requirement, docs §8) — START_STICKY asks the OS to
        // recreate this service (with a null intent) if it's killed under
        // memory pressure; BootReceiver handles the reboot case separately,
        // since a killed *process* still has this service's sticky restart,
        // but a full reboot starts with no services running at all.
        return TokenStore.P2P_RELAY_ALWAYS.equals(relayMode) ? START_STICKY : START_NOT_STICKY;
    }

    private void stopRelay() {
        if (agent != null) {
            agent.stop();
            agent = null;
        }
        new TokenStore(this).saveP2pRelayState(TokenStore.P2P_RELAY_OFF, 0L);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, VpnApp.VPN_STATUS_CHANNEL_ID)
                .setContentTitle(getString(R.string.p2p_relay_notification_title))
                .setContentText(getString(R.string.p2p_relay_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        // Best-effort direct cleanup (the process may be dying right now, so
        // relayExecutor might never get to run a queued task) plus
        // shutdownNow() to interrupt anything already in flight and drop
        // anything still queued, rather than leaking the thread.
        if (agent != null) {
            agent.stop();
            agent = null;
        }
        relayExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
