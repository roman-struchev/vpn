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
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
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
