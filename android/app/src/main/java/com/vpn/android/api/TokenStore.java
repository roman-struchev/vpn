package com.vpn.android.api;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** Persists the JWT issued by /api/v1/auth/* in a Keystore-backed encrypted prefs file. */
public class TokenStore {

    private static final String PREFS_FILE = "vpn_secure_prefs";
    private static final String KEY_TOKEN = "auth_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_DEVICE_ID = "device_id";

    private final SharedPreferences prefs;

    public TokenStore(Context context) {
        this.prefs = create(context.getApplicationContext());
    }

    private static SharedPreferences create(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            // Keystore is unavailable (very old/broken device). Falling back to a plain
            // file is strictly worse than crashing on every launch; the JWT is short-lived
            // and re-issuable via login, so this is an acceptable degraded path for the MVP.
            return context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE);
        }
    }

    public void save(String token, long userId) {
        prefs.edit().putString(KEY_TOKEN, token).putLong(KEY_USER_ID, userId).apply();
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, null);
    }

    public long getUserId() {
        return prefs.getLong(KEY_USER_ID, -1);
    }

    public boolean isLoggedIn() {
        return getToken() != null;
    }

    /**
     * The server-assigned Device row for this physical install, used to auto-
     * register/touch on connect instead of asking the user to manually "add a
     * device" (see XrayVpnService#registerOrTouchDevice). -1 means "not
     * registered yet" — including after a logout, since clear() wipes this
     * too; re-registering once on next login is harmless (just one extra
     * Device row) and keeps this simple.
     */
    public void saveDeviceId(long deviceId) {
        prefs.edit().putLong(KEY_DEVICE_ID, deviceId).apply();
    }

    public long getDeviceId() {
        return prefs.getLong(KEY_DEVICE_ID, -1);
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
