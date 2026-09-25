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
    // Whether the stored session belongs to this install's own device-trial
    // account (see ApiClient#deviceAuth). Decides what an expired session
    // turns into: a guest is silently signed back in, anyone else is sent to
    // the sign-in form. Absent on installs from before it existed until the
    // first profile load fills it in (see setDeviceAccount).
    private static final String KEY_DEVICE_ACCOUNT = "device_account";
    // Where each region's latency is measured: one node's host:port and when
    // it was learnt, from the subscription links the app fetches anyway. Lets
    // the ping skip a links request of its own every time it runs.
    private static final String KEY_PING_TARGET_PREFIX = "ping_target_";
    // P2P relay mode (docs/research/P2P_RELAY_FEASIBILITY.md §8) — this
    // device's own persisted node identity, separate from the user's JWT
    // above, plus a locally-cached copy of the last relay window the user
    // picked. The server (Node#isEligibleForRelay) is the real enforcement
    // point for whether relaying is actually allowed right now; these are
    // only for (a) reconnecting as the same node instead of re-registering
    // every time, and (b) BootReceiver deciding whether to restart the relay
    // service after a device reboot without needing a network round-trip
    // first.
    private static final String KEY_P2P_NODE_ID = "p2p_node_id";
    private static final String KEY_P2P_NODE_TOKEN = "p2p_node_token";
    private static final String KEY_P2P_RELAY_MODE = "p2p_relay_mode";
    private static final String KEY_P2P_RELAY_EXPIRES_AT = "p2p_relay_expires_at_epoch_ms";
    // Which timed option produced the current window (1h vs 8h). The expiry
    // alone cannot answer that — an 8h window with 40 minutes left looks
    // exactly like a 1h one — so the settings screen used to re-select the
    // wrong option late in a long window. The desktop client persists the
    // same thing for the same reason (TokenStore#p2pRelayDurationMs).
    private static final String KEY_P2P_RELAY_DURATION_MS = "p2p_relay_duration_ms";
    // The relay NODE's own declared location — geo-IP auto-detected once by
    // P2pRelayAgent#start on first-ever registration (see GeoLocale#detectNodeRegion),
    // then cached here and reused on every later start. Unrelated to any VPN
    // egress region preference the app keeps elsewhere.
    private static final String KEY_P2P_RELAY_REGION = "p2p_relay_region";
    // Whether this account already accepted the P2P terms, as last reported by
    // GET /p2p/status (or as just accepted from this device). Consent is a
    // one-time, server-side fact; caching it locally is what lets the settings
    // screen know it *before* the status call returns, instead of rendering an
    // unaccepted state that flips a moment later.
    private static final String KEY_P2P_TERMS_ACCEPTED = "p2p_terms_accepted";
    // Why relaying was refused on the current network (a NatCheck.Verdict
    // name), until it is switched off or starts fine — see NatCheck.
    private static final String KEY_P2P_RELAY_UNSUPPORTED = "p2p_relay_unsupported_network";
    public static final String P2P_RELAY_OFF = "OFF";
    public static final String P2P_RELAY_TIMED = "TIMED";
    public static final String P2P_RELAY_ALWAYS = "ALWAYS";

    private final SharedPreferences prefs;

    public TokenStore(Context context) {
        this.prefs = create(context.getApplicationContext());
    }

    TokenStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    // One instance per process. Building EncryptedSharedPreferences (Keystore MasterKey + Tink
    // keyset decryption) is slow, and every activity/fragment/service constructs its own
    // TokenStore on the main thread — on a loaded device that stacked up into multi-second
    // stalls opening a screen (reproduced as "isn't responding" dialogs opening the P2P and
    // sign-in screens on an emulator).
    private static volatile SharedPreferences sharedPrefs;

    private static SharedPreferences create(Context context) {
        SharedPreferences cached = sharedPrefs;
        if (cached != null) return cached;
        synchronized (TokenStore.class) {
            if (sharedPrefs == null) sharedPrefs = createUncached(context);
            return sharedPrefs;
        }
    }

    private static SharedPreferences createUncached(Context context) {
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

    /** A new session plus what kind of account it is — see KEY_DEVICE_ACCOUNT. */
    public void saveSession(String token, long userId, boolean deviceAccount) {
        prefs.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_USER_ID, userId)
                .putBoolean(KEY_DEVICE_ACCOUNT, deviceAccount)
                .apply();
    }

    /** Replaces the token of the current session, keeping everything else. */
    public void replaceToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    /** Drops only the dead token: device id, region and settings stay, so signing back in picks up where it left off. */
    public void clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply();
    }

    /** null when not known yet (a session saved before this was recorded). */
    public Boolean isDeviceAccount() {
        if (!prefs.contains(KEY_DEVICE_ACCOUNT)) return null;
        return prefs.getBoolean(KEY_DEVICE_ACCOUNT, false);
    }

    /** Filled in from the profile's isGuest, which is authoritative. */
    public void setDeviceAccount(boolean deviceAccount) {
        prefs.edit().putBoolean(KEY_DEVICE_ACCOUNT, deviceAccount).apply();
    }

    public void savePingTarget(String region, String host, int port, long nowMs) {
        prefs.edit().putString(KEY_PING_TARGET_PREFIX + region, host + "|" + port + "|" + nowMs).apply();
    }

    /** {host, port} learnt no longer than maxAgeMs ago, or null. */
    public String[] getPingTarget(String region, long nowMs, long maxAgeMs) {
        String raw = prefs.getString(KEY_PING_TARGET_PREFIX + region, null);
        if (raw == null) return null;
        String[] parts = raw.split("\\|");
        if (parts.length != 3) return null;
        try {
            if (nowMs - Long.parseLong(parts[2]) > maxAgeMs) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{parts[0], parts[1]};
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

    public void saveP2pNode(long nodeId, String nodeToken) {
        prefs.edit().putLong(KEY_P2P_NODE_ID, nodeId).putString(KEY_P2P_NODE_TOKEN, nodeToken).apply();
    }

    public long getP2pNodeId() {
        return prefs.getLong(KEY_P2P_NODE_ID, -1);
    }

    public String getP2pNodeToken() {
        return prefs.getString(KEY_P2P_NODE_TOKEN, null);
    }

    /** One of {@link #P2P_RELAY_OFF}, {@link #P2P_RELAY_TIMED}, {@link #P2P_RELAY_ALWAYS} — defaults to OFF for an install that never touched this feature. */
    public String getP2pRelayMode() {
        return prefs.getString(KEY_P2P_RELAY_MODE, P2P_RELAY_OFF);
    }

    public void saveP2pRelayState(String relayMode, long relayExpiresAtEpochMs) {
        prefs.edit()
                .putString(KEY_P2P_RELAY_MODE, relayMode)
                .putLong(KEY_P2P_RELAY_EXPIRES_AT, relayExpiresAtEpochMs)
                .apply();
    }

    /** SYMMETRIC / NO_UDP when relaying was refused on this network, else null. */
    public String getP2pRelayUnsupportedNetwork() {
        return prefs.getString(KEY_P2P_RELAY_UNSUPPORTED, null);
    }

    public void saveP2pRelayUnsupportedNetwork(String verdict) {
        prefs.edit().putString(KEY_P2P_RELAY_UNSUPPORTED, verdict).apply();
    }

    public long getP2pRelayExpiresAt() {
        return prefs.getLong(KEY_P2P_RELAY_EXPIRES_AT, 0L);
    }

    /** How long the current timed window was chosen to last, or 0 if unknown — see {@link #KEY_P2P_RELAY_DURATION_MS}. */
    public long getP2pRelayDurationMs() {
        return prefs.getLong(KEY_P2P_RELAY_DURATION_MS, 0L);
    }

    public void saveP2pRelayDurationMs(long durationMs) {
        prefs.edit().putLong(KEY_P2P_RELAY_DURATION_MS, durationMs).apply();
    }

    public String getP2pRelayRegion() {
        return prefs.getString(KEY_P2P_RELAY_REGION, null);
    }

    public void saveP2pRelayRegion(String region) {
        prefs.edit().putString(KEY_P2P_RELAY_REGION, region).apply();
    }

    /** Cached copy of the server's "terms accepted" flag — see {@link #KEY_P2P_TERMS_ACCEPTED}. */
    public boolean isP2pTermsAccepted() {
        return prefs.getBoolean(KEY_P2P_TERMS_ACCEPTED, false);
    }

    public void saveP2pTermsAccepted(boolean accepted) {
        prefs.edit().putBoolean(KEY_P2P_TERMS_ACCEPTED, accepted).apply();
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
