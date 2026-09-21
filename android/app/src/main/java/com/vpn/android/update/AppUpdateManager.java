package com.vpn.android.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.vpn.android.R;
import com.vpn.android.util.Async;

import java.io.File;

/**
 * Drives the "check GitHub Releases -> prompt -> download -> install" flow
 * for this self-distributed APK (see AppUpdateChecker). Android still
 * requires one explicit user tap on the system package-installer screen
 * before the update is actually applied; that step can't be skipped without
 * root/device-owner privileges, so this only automates everything up to it.
 */
public final class AppUpdateManager {

    private static final String PREFS = "app_update_prefs";
    private static final String KEY_DISMISSED_TAG = "dismissed_tag";
    private static final String APK_FILE_NAME = "update.apk";

    private final Context appContext;

    public AppUpdateManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Checks GitHub in the background and, if a newer release exists that the
     * user hasn't already dismissed, shows the update dialog. Safe to call
     * from an Activity's onCreate/onResume; failures (offline, GitHub
     * unreachable) are swallowed so this never disrupts normal app use.
     */
    public void checkAndPrompt(Context uiContext) {
        Async.run(
                () -> new AppUpdateChecker().checkForUpdate(),
                update -> {
                    if (update == null) return;
                    if (update.tag.equals(prefs().getString(KEY_DISMISSED_TAG, null))) return;
                    showUpdateDialog(uiContext, update);
                },
                error -> { /* offline or GitHub unreachable — silently retry next launch */ });
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void showUpdateDialog(Context context, AppUpdateChecker.UpdateInfo update) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.update_available_title)
                .setMessage(context.getString(R.string.update_available_message, update.version))
                .setCancelable(false)
                .setPositiveButton(R.string.update_download_action, (d, w) -> startDownload(context, update))
                .setNegativeButton(R.string.update_later_action, (d, w) ->
                        prefs().edit().putString(KEY_DISMISSED_TAG, update.tag).apply())
                .show();
    }

    private void startDownload(Context context, AppUpdateChecker.UpdateInfo update) {
        // getExternalFilesDir can return null if external storage isn't
        // currently mounted (rare, but documented) — fall back to internal
        // storage rather than crash; file_paths.xml exposes both to FileProvider.
        File dir = appContext.getExternalFilesDir(null);
        if (dir == null) dir = appContext.getFilesDir();
        File apkFile = new File(dir, APK_FILE_NAME);
        if (apkFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            apkFile.delete();
        }

        DownloadManager downloadManager = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle(context.getString(R.string.update_downloading_title))
                .setDescription(update.version)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(apkFile))
                .setMimeType("application/vnd.android.package-archive");

        long downloadId = downloadManager.enqueue(request);
        Toast.makeText(context, R.string.update_downloading_title, Toast.LENGTH_SHORT).show();
        registerDownloadReceiver(downloadManager, downloadId, apkFile);
    }

    private void registerDownloadReceiver(DownloadManager downloadManager, long expectedId, File apkFile) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != expectedId) return;
                appContext.unregisterReceiver(this);
                if (isSuccessful(downloadManager, expectedId) && apkFile.exists()) {
                    promptInstall(apkFile);
                } else {
                    Toast.makeText(appContext, R.string.update_download_failed, Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // RECEIVER_EXPORTED, not NOT_EXPORTED. This broadcast comes from
            // the system's DownloadManager — another UID — and NOT_EXPORTED
            // means "only broadcasts my own app sends", so on Android 13+ it
            // never arrived: the download finished and the install screen
            // simply never appeared (reported live).
            //
            // What keeps that safe is not the export flag: any app can send
            // ACTION_DOWNLOAD_COMPLETE, and could before this change too. It
            // is that nothing here trusts the intent beyond its id — the file
            // is ours, and its download must be SUCCESSFUL according to
            // DownloadManager itself (isSuccessful), queried by the id we
            // enqueued. A spoofed broadcast can at most make us check early.
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
    }

    private boolean isSuccessful(DownloadManager downloadManager, long downloadId) {
        try (Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            return status == DownloadManager.STATUS_SUCCESSFUL;
        }
    }

    private void promptInstall(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !appContext.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(appContext, R.string.update_install_permission_needed, Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + appContext.getPackageName()))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(settingsIntent);
            return;
        }
        installApk(apkFile);
    }

    private void installApk(File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(
                appContext, appContext.getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appContext.startActivity(installIntent);
    }
}
