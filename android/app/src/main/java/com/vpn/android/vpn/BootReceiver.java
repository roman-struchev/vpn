package com.vpn.android.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.vpn.android.api.TokenStore;

/**
 * Handles device boot completion (BOOT_COMPLETED) to automatically start
 * the VPN tunnel if enabled by user in settings (TokenStore#isAutoConnectOnBoot).
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
    }
}
