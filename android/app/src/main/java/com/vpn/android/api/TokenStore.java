package com.vpn.android.api;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/** Persists the JWT issued by /api/v1/auth/* in a Keystore-backed encrypted prefs file. */
public class TokenStore {

    private static final String PREFS_FILE = "vpn_secure_prefs";
    private static final String KEY_TOKEN = "auth_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SELECTED_REGION = "selected_region";
    private static final String KEY_DEVICE_UUID = "device_uuid";

    private final SharedPreferences prefs;

    public TokenStore(Context context) {
        this.prefs = create(context.getApplicationContext());
    }

    TokenStore(SharedPreferences prefs) {
        this.prefs = prefs;
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

    /**
     * User's pinned connection region (e.g. "nl-ams"), or null for
     * "auto"/best-available — today's implicit behavior. Set from the
     * ConnectFragment region picker, read by XrayVpnService#loadProfileAndConnect.
     * Wiped by clear() along with everything else on logout, same as deviceId —
     * simplest behavior for the MVP, re-picking a region after logging back in
     * is a minor inconvenience at worst.
     */
    public String getSelectedRegion() {
        return prefs.getString(KEY_SELECTED_REGION, null);
    }

    public void saveSelectedRegion(String region) {
        if (region == null) {
            prefs.edit().remove(KEY_SELECTED_REGION).apply();
        } else {
            prefs.edit().putString(KEY_SELECTED_REGION, region).apply();
        }
    }

    /**
     * Stable per-install identifier, generated once and persisted on first
     * access — works even before any login has ever happened, unlike the
     * rest of this store. Sent to POST /api/v1/auth/device so a fresh install
     * can start on the trial tariff without registration (see
     * ApiClient#deviceAuth and LoginActivity's auto-login-on-launch flow).
     * Mirrors desktop's TokenStore#getOrCreateDeviceUuid.
     */
    public String getOrCreateDeviceUuid() {
        String existing = prefs.getString(KEY_DEVICE_UUID, null);
        if (existing != null) return existing;
        String deviceUuid = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_UUID, deviceUuid).apply();
        return deviceUuid;
    }

    /**
     * Clears the session (token, userId, deviceId) but deliberately preserves
     * deviceUuid and selectedRegion — same per-install fields save() preserves
     * across a re-login. Wiping deviceUuid here allowed unlimited trial resets
     * upon logout. Keeping it here is safe because the server guards against
     * password-less re-entry into upgraded accounts.
     */
    public void clear() {
        String deviceUuid = prefs.getString(KEY_DEVICE_UUID, null);
        String selectedRegion = prefs.getString(KEY_SELECTED_REGION, null);
        SharedPreferences.Editor editor = prefs.edit().clear();
        if (deviceUuid != null) {
            editor.putString(KEY_DEVICE_UUID, deviceUuid);
        }
        if (selectedRegion != null) {
            editor.putString(KEY_SELECTED_REGION, selectedRegion);
        }
        editor.apply();
    }
}
