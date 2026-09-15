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

    private P2pRelayAgent agent;

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRelay();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        String relayMode = intent != null ? intent.getStringExtra(EXTRA_RELAY_MODE) : TokenStore.P2P_RELAY_OFF;
        long relayExpiresAt = intent != null ? intent.getLongExtra(EXTRA_RELAY_EXPIRES_AT, 0L) : 0L;
        if (relayMode == null) relayMode = TokenStore.P2P_RELAY_OFF;

        TokenStore tokenStore = new TokenStore(this);
        tokenStore.saveP2pRelayState(relayMode, relayExpiresAt);

        if (TokenStore.P2P_RELAY_OFF.equals(relayMode)) {
            stopRelay();
            return START_NOT_STICKY;
        }

        if (agent == null) {
            agent = new P2pRelayAgent(this, tokenStore, new ApiClient(tokenStore), message -> {
                Log.w(TAG, "relay agent error: " + message);
            });
            try {
                agent.start(relayMode, relayExpiresAt);
            } catch (Exception e) {
                Log.e(TAG, "failed to start P2P relay agent", e);
                stopRelay();
                return START_NOT_STICKY;
            }
        } else {
            agent.updateRelayMode(relayMode, relayExpiresAt);
        }

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
        if (agent != null) {
            agent.stop();
            agent = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
