package com.vpn.android.vpn;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.vpn.android.R;
import com.vpn.android.ui.MainActivity;
import com.vpn.android.vpn.state.ConnectionState;

/**
 * Quick Settings tile enabling one-tap VPN connect / disconnect directly
 * from Android notification shade.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class VpnTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            // VPN permission not granted yet: open main activity to prompt user
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(mainIntent);
            return;
        }

        ConnectionState state = VpnStatusBus.state.getValue();
        // Same rule as the in-app button: every state the tile shows as
        // active is one a tap turns off. RECONNECTING used to send CONNECT,
        // starting a second connect flow next to the scheduled retry.
        if (isActive(state)) {
            Intent intent = new Intent(this, XrayVpnService.class);
            intent.setAction(XrayVpnService.ACTION_DISCONNECT);
            startService(intent);
        } else {
            Intent intent = new Intent(this, XrayVpnService.class);
            intent.setAction(XrayVpnService.ACTION_CONNECT);
            ContextCompat.startForegroundService(this, intent);
        }
        updateTileState();
    }

    static boolean isActive(ConnectionState state) {
        return state == ConnectionState.CONNECTED
                || state == ConnectionState.CONNECTING
                || state == ConnectionState.RECONNECTING;
    }

    /**
     * Asks the system to call onStartListening again, so a tile that is
     * visible while the state changes (shade pulled down during a connect)
     * does not keep showing the old one.
     */
    public static void requestRefresh(android.content.Context context) {
        try {
            TileService.requestListeningState(context,
                    new android.content.ComponentName(context, VpnTileService.class));
        } catch (Exception ignored) {
            // tile not added, or not allowed from here — nothing to refresh
        }
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        ConnectionState state = VpnStatusBus.state.getValue();
        if (state == ConnectionState.CONNECTED) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(getString(R.string.state_connected));
        } else if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(getString(R.string.state_connecting));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.app_name));
        }
        tile.updateTile();
    }
}
