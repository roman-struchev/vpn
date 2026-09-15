package com.vpn.android.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.vpn.android.api.TokenStore;
import com.vpn.android.p2p.P2pRelayService;

/**
 * Handles device boot completion (BOOT_COMPLETED) to automatically start
 * the VPN tunnel if enabled by user in settings (TokenStore#isAutoConnectOnBoot),
 * and separately to resume P2P relay mode (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.5) if it was left in ALWAYS mode — an explicit
 * requirement that relaying "survive both app restart and device reboot".
 * TIMED mode is deliberately NOT resumed here: its real deadline is enforced
 * server-side (Node#isEligibleForRelay), so a reboot mid-window either still
 * has time left (worth resuming) or doesn't (the server would refuse
 * signaling anyway) — but re-registering blind on every boot for a mode the
 * user only meant to run once is more surprising than useful, so we only
 * ever auto-resume the mode the user explicitly asked to persist forever.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        TokenStore tokenStore = new TokenStore(context);
        if (tokenStore.isLoggedIn() && tokenStore.isAutoConnectOnBoot()) {
            Log.i(TAG, "Device booted and auto-connect enabled, launching XrayVpnService");
            Intent serviceIntent = new Intent(context, XrayVpnService.class);
            serviceIntent.setAction(XrayVpnService.ACTION_CONNECT);
            try {
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start VPN service on boot", e);
            }
        }

        if (tokenStore.isLoggedIn() && TokenStore.P2P_RELAY_ALWAYS.equals(tokenStore.getP2pRelayMode())) {
            Log.i(TAG, "Device booted with P2P relay mode ALWAYS, restarting P2pRelayService");
            Intent relayIntent = new Intent(context, P2pRelayService.class);
            relayIntent.putExtra(P2pRelayService.EXTRA_RELAY_MODE, TokenStore.P2P_RELAY_ALWAYS);
            try {
                ContextCompat.startForegroundService(context, relayIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start P2P relay service on boot", e);
            }
        }
    }
}
