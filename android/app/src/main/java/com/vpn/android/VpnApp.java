package com.vpn.android;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class VpnApp extends Application {

    public static final String VPN_STATUS_CHANNEL_ID = "vpn_status";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        // minSdk is 26 (Build.VERSION_CODES.O): notification channels always exist.
        NotificationChannel channel = new NotificationChannel(
                VPN_STATUS_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
