package com.vpn.android.api;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/** Persists the JWT issued by /api/v1/auth/* in a Keystore-backed encrypted prefs file. */
public class TokenStore {

    private static final String PREFS_FILE = "vpn_secure_prefs";
    private static final String KEY_TOKEN = "auth_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SELECTED_REGION = "selected_region";
    private static final String KEY_DEVICE_UUID = "device_uuid";
    private static final String KEY_BYPASS_RUSSIAN_TRAFFIC = "bypass_russian_traffic";
    private static final String KEY_RUSSIAN_ROUTING_MODE = "russian_routing_mode";
    public static final String RUSSIAN_ROUTING_OFF = "off";
    public static final String RUSSIAN_ROUTING_BYPASS = "bypassRu";
    public static final String RUSSIAN_ROUTING_ONLY_RU = "onlyRu";
    private static final String KEY_ORIGINAL_IP_IS_RUSSIA = "original_ip_is_russia";
    private static final String KEY_AUTO_CONNECT_ON_BOOT = "auto_connect_on_boot";
    private static final String KEY_DISALLOWED_APPS = "disallowed_apps";

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
     * @deprecated superseded by {@link #getRussianRoutingMode()}'s 3-way mode
     * (off / bypassRu / onlyRu). Kept only so {@link #getRussianRoutingMode()}
     * can migrate a pre-existing boolean pref on first read after an app
     * update; new code should not call this.
     */
    @Deprecated
    public boolean isBypassRussianTraffic() {
        return prefs.getBoolean(KEY_BYPASS_RUSSIAN_TRAFFIC, false);
    }

    /** @deprecated see {@link #isBypassRussianTraffic()} — kept for the same reason. */
    @Deprecated
    public void setBypassRussianTraffic(boolean enabled) {
        prefs.edit().putBoolean(KEY_BYPASS_RUSSIAN_TRAFFIC, enabled).apply();
    }

    /**
     * One of {@link #RUSSIAN_ROUTING_OFF}, {@link #RUSSIAN_ROUTING_BYPASS} (RU
     * sites/apps go direct, everything else through the VPN — for a user
     * physically in Russia) or {@link #RUSSIAN_ROUTING_ONLY_RU} (the reverse:
     * only RU sites/apps go through the VPN, through a Russia-located node —
     * for a Russian-speaking user physically outside Russia who wants to
     * reach RU-geo-restricted services). Migrates the old boolean pref
     * (true -> bypassRu, false/absent -> off) the first time this is read
     * after an app update, so existing installs keep their prior behavior.
     */
    public String getRussianRoutingMode() {
        String mode = prefs.getString(KEY_RUSSIAN_ROUTING_MODE, null);
        if (mode != null) return mode;
        String migrated = prefs.getBoolean(KEY_BYPASS_RUSSIAN_TRAFFIC, false) ? RUSSIAN_ROUTING_BYPASS : RUSSIAN_ROUTING_OFF;
        prefs.edit().putString(KEY_RUSSIAN_ROUTING_MODE, migrated).apply();
        return migrated;
    }

    public void setRussianRoutingMode(String mode) {
        prefs.edit().putString(KEY_RUSSIAN_ROUTING_MODE, mode).apply();
    }

    /**
     * One-time geo-IP result for this install's original public IP (before
     * ever connecting the VPN), used alongside the device locale to decide
     * whether to show the Russian-routing control at all — see
     * ConnectFragment. {@code null} means "not looked up yet."
     */
    public Boolean getOriginalIpIsRussia() {
        if (!prefs.contains(KEY_ORIGINAL_IP_IS_RUSSIA)) return null;
        return prefs.getBoolean(KEY_ORIGINAL_IP_IS_RUSSIA, false);
    }

    public void saveOriginalIpIsRussia(boolean isRussia) {
        prefs.edit().putBoolean(KEY_ORIGINAL_IP_IS_RUSSIA, isRussia).apply();
    }

    public boolean isAutoConnectOnBoot() {
        return prefs.getBoolean(KEY_AUTO_CONNECT_ON_BOOT, false);
    }

    public void setAutoConnectOnBoot(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT_ON_BOOT, enabled).apply();
    }

    public Set<String> getDisallowedApps() {
        return prefs.getStringSet(KEY_DISALLOWED_APPS, Collections.emptySet());
    }

    public void setDisallowedApps(Set<String> apps) {
        prefs.edit().putStringSet(KEY_DISALLOWED_APPS, apps).apply();
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
        boolean hasOriginalIpIsRussia = prefs.contains(KEY_ORIGINAL_IP_IS_RUSSIA);
        boolean originalIpIsRussia = prefs.getBoolean(KEY_ORIGINAL_IP_IS_RUSSIA, false);
        SharedPreferences.Editor editor = prefs.edit().clear();
        if (deviceUuid != null) {
            editor.putString(KEY_DEVICE_UUID, deviceUuid);
        }
        if (selectedRegion != null) {
            editor.putString(KEY_SELECTED_REGION, selectedRegion);
        }
        // A device/install property (which country this phone first launched
        // from), not session state — losing it on logout would just trigger a
        // redundant geo-IP re-lookup on next launch, not a functional bug, but
        // there's no reason to throw it away.
        if (hasOriginalIpIsRussia) {
            editor.putBoolean(KEY_ORIGINAL_IP_IS_RUSSIA, originalIpIsRussia);
        }
        editor.apply();
    }
}
