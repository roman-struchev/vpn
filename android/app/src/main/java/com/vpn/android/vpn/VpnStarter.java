package com.vpn.android.vpn;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.vpn.android.R;
import com.vpn.android.vpn.state.ConnectionState;

/**
 * Sending the service a "your settings changed" nudge, from wherever those
 * settings are edited. Shared because the connect screen and the settings
 * screen both change things the running session baked in at connect time,
 * and a second copy of this would be a second chance to forget the toast or
 * the foreground-start rule.
 */
public final class VpnStarter {

    private VpnStarter() {
    }

    /**
     * Applies a just-changed region / RU-routing mode to a live tunnel. Both
     * are baked into the running session (node list, Xray rules, TUN app
     * filter), so without this the change silently does nothing until the
     * next manual connect. A no-op when nothing is connected.
     */
    public static void reconnectIfActive(Context context) {
        ConnectionState state = VpnStatusBus.state.getValue();
        if (state != ConnectionState.CONNECTED
                && state != ConnectionState.CONNECTING
                && state != ConnectionState.RECONNECTING) {
            return;
        }
        Intent intent = new Intent(context, XrayVpnService.class).setAction(XrayVpnService.ACTION_RECONNECT);
        ContextCompat.startForegroundService(context, intent);
        Toast.makeText(context, R.string.reconnecting_with_new_settings, Toast.LENGTH_SHORT).show();
    }
}
