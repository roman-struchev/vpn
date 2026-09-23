package com.vpn.android.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vpn.android.BuildConfig;
import com.vpn.android.api.model.AuthResponse;
import com.vpn.android.api.model.DeviceDto;
import com.vpn.android.api.model.P2pStatusResponse;
import com.vpn.android.api.model.RegionInfo;
import com.vpn.android.api.model.ExitsResponse;
import com.vpn.android.api.model.RelayInfo;
import com.vpn.android.api.model.RelaysResponse;
import com.vpn.android.api.model.TariffInfo;
import com.vpn.android.api.model.RegionsResponse;
import com.vpn.android.api.model.RoutingConfigResponse;
import com.vpn.android.api.model.SubscriptionLinksResponse;
import com.vpn.android.api.model.UserProfile;
import com.vpn.android.api.model.WebHandoffResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.vpn.android.vpn.xray.VlessUri;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Synchronous REST client for the server API surface this app uses
 * (server/src/main/java/com/vpn/server/controller: AuthController,
 * UserController, ClientController). Callers are responsible for running
 * these off the main thread.
 */
public class ApiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ApiHostRotation hostRotation;
    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final TokenStore tokenStore;

    /**
     * Told when a session has ended for good (expired and not renewable
     * without the user) — the UI's cue to show the sign-in form. Called on
     * whatever thread noticed; a listener that touches views must post.
     */
    public interface SessionExpiredListener {
        void onSessionExpired();
    }

    private static volatile SessionExpiredListener sessionExpiredListener;

    public static void setSessionExpiredListener(SessionExpiredListener listener) {
        sessionExpiredListener = listener;
    }

    /** The refresh throttle is process-wide; tests each start from a clean slate. */
    static void resetSessionStateForTests() {
        lastRefreshAttemptMs = 0;
    }

    /** Renew the token once it has less than this left. */
    static final long REFRESH_WHEN_LEFT_SEC = 7L * 24 * 3600;
    /** And at most this often, so an unreachable server is not asked on every call. */
    private static final long REFRESH_ATTEMPT_INTERVAL_MS = 3_600_000L;
    private static final Object SESSION_LOCK = new Object();
    private static volatile long lastRefreshAttemptMs = 0;

    /** How long a remembered ping target (see pingSelectedRegion) stays usable. */
    private static final long PING_TARGET_MAX_AGE_MS = 24L * 3600 * 1000;

    public ApiClient(TokenStore tokenStore) {
        this(hostsFromBuildConfig(), tokenStore);
    }

    /**
     * For the VPN service: every socket comes from {@code socketFactory},
     * which there is one that VpnService#protect()s it. Otherwise, once the
     * TUN is up, the service's own API calls (signaling for a relay, the
     * reload after a failed round) would be routed into the very tunnel they
     * are trying to repair.
     */
    public ApiClient(TokenStore tokenStore, javax.net.SocketFactory socketFactory) {
        this(hostsFromBuildConfig(), tokenStore, buildHttp(socketFactory));
    }

    public ApiClient(String baseUrl, TokenStore tokenStore) {
        this(List.of(baseUrl), tokenStore);
    }

    /** @param baseUrls primary host first, then backup domains (Phase 10: "резервные домены API"). */
    public ApiClient(List<String> baseUrls, TokenStore tokenStore) {
        this(baseUrls, tokenStore, null);
    }

    ApiClient(List<String> baseUrls, TokenStore tokenStore, OkHttpClient customHttp) {
        List<String> normalized = new ArrayList<>();
        for (String url : baseUrls) {
            normalized.add(url.endsWith("/") ? url : url + "/");
        }
        this.hostRotation = new ApiHostRotation(normalized);
        this.tokenStore = tokenStore;

        this.http = customHttp != null ? customHttp : buildHttp(null);
    }

    private static OkHttpClient buildHttp(javax.net.SocketFactory socketFactory) {
        OkHttpClient.Builder base = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS);
        if (socketFactory != null) base.socketFactory(socketFactory);
        OkHttpClient bootstrap = base.build();
        return bootstrap.newBuilder()
                .dns(DohDns.create(bootstrap))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    private static List<String> hostsFromBuildConfig() {
        List<String> hosts = new ArrayList<>();
        hosts.add(BuildConfig.API_BASE_URL);
        if (BuildConfig.API_BASE_URLS_BACKUP != null && !BuildConfig.API_BASE_URLS_BACKUP.isBlank()) {
            for (String backup : BuildConfig.API_BASE_URLS_BACKUP.split(",")) {
                if (!backup.isBlank()) hosts.add(backup.trim());
            }
        }
        return hosts;
    }

    public AuthResponse login(String email, String password) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        String deviceUuid = tokenStore != null ? tokenStore.getOrCreateDeviceUuid() : null;
        if (deviceUuid != null && !deviceUuid.isBlank()) {
            body.addProperty("deviceUuid", deviceUuid);
        }
        AuthResponse resp = post("api/v1/auth/login", body, AuthResponse.class, false);
        if (tokenStore != null) {
            tokenStore.saveSession(resp.token, resp.userId, false);
        }
        return resp;
    }

    /**
     * Signs in with a one-time code from the Telegram bot (/login) or the
     * web dashboard — the only way into the app for an account made in
     * Telegram, which has no password. Sends the deviceUuid like login(),
     * so this install's trial account folds into it.
     */
    public AuthResponse loginWithCode(String code) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        String deviceUuid = tokenStore != null ? tokenStore.getOrCreateDeviceUuid() : null;
        if (deviceUuid != null && !deviceUuid.isBlank()) {
            body.addProperty("deviceUuid", deviceUuid);
        }
        AuthResponse resp = post("api/v1/auth/code", body, AuthResponse.class, false);
        if (tokenStore != null) {
            tokenStore.saveSession(resp.token, resp.userId, false);
        }
        return resp;
    }

    /** Sends a reset code to the account's Telegram/email; the same answer whether or not it exists. */
    public void requestPasswordReset(String email) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        post("api/v1/auth/password-reset/request", body, JsonObject.class, false);
    }

    public AuthResponse confirmPasswordReset(String email, String code, String newPassword) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("code", code);
        body.addProperty("newPassword", newPassword);
        AuthResponse resp = post("api/v1/auth/password-reset/confirm", body, AuthResponse.class, false);
        if (tokenStore != null) {
            tokenStore.saveSession(resp.token, resp.userId, false);
        }
        return resp;
    }

    public AuthResponse register(String email, String password, String referralCode) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        if (referralCode != null) body.addProperty("referralCode", referralCode);
        AuthResponse resp = post("api/v1/auth/register", body, AuthResponse.class, false);
        if (tokenStore != null) {
            tokenStore.saveSession(resp.token, resp.userId, false);
        }
        return resp;
    }

    /**
     * Converts the currently-signed-in guest/device-trial account into a
     * real, credentialed one in place — same user id, balance, and active
     * trial subscription, just adding an email+password so it survives
     * logout/reinstall. The register-time counterpart to login()'s merge;
     * must be called while still holding the guest's token (authenticated = true),
     * not after switching away from it. See POST /api/v1/auth/upgrade.
     */
    public AuthResponse upgradeGuest(String email, String password) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        AuthResponse resp = post("api/v1/auth/upgrade", body, AuthResponse.class, true);
        if (tokenStore != null) {
            tokenStore.saveSession(resp.token, resp.userId, false);
        }
        return resp;
    }

    public AuthResponse googleAuth(String idToken, String referralCode) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("idToken", idToken);
        if (referralCode != null) body.addProperty("referralCode", referralCode);
        String deviceUuid = tokenStore != null ? tokenStore.getOrCreateDeviceUuid() : null;
        if (deviceUuid != null && !deviceUuid.isBlank()) {
            body.addProperty("deviceUuid", deviceUuid);
        }
        AuthResponse resp = post("api/v1/auth/google", body, AuthResponse.class, false);
        if (tokenStore != null) {
            tokenStore.saveSession(resp.token, resp.userId, false);
        }
        return resp;
    }

    /**
     * No-signup trial login (see server DeviceAuthService): idempotent per
     * deviceUuid — repeat calls just log the same auto-created account back
     * in, granting a trial subscription on first creation (no real expiry
     * date anymore, only a traffic cap — see BillingService.NO_EXPIRY_DAYS
     * server-side). Used by
     * LoginActivity's auto-login-on-launch flow so a fresh install can start
     * using the app without registration.
     */
    public AuthResponse deviceAuth(String deviceUuid, String referralCode) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("deviceUuid", deviceUuid);
        if (referralCode != null) body.addProperty("referralCode", referralCode);
        AuthResponse resp = post("api/v1/auth/device", body, AuthResponse.class, false);
        if (tokenStore != null) {
            tokenStore.saveSession(resp.token, resp.userId, true);
        }
        return resp;
    }

    public UserProfile getProfile() throws ApiException, IOException {
        return get("api/v1/user/profile", UserProfile.class);
    }

    public List<DeviceDto> getDevices() throws ApiException, IOException {
        DeviceDto[] arr = get("api/v1/user/devices", DeviceDto[].class);
        return List.of(arr);
    }

    public DeviceDto addDevice(String deviceName, String platform) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("deviceName", deviceName);
        body.addProperty("platform", platform);
        JsonObject resp = post("api/v1/user/devices", body, JsonObject.class, true);
        DeviceDto dto = new DeviceDto();
        dto.id = resp.get("deviceId").getAsLong();
        dto.deviceName = resp.get("deviceName").getAsString();
        dto.platform = resp.get("platform").getAsString();
        dto.isActive = true;
        return dto;
    }

    public void deleteDevice(long deviceId) throws ApiException, IOException {
        delete("api/v1/user/devices/" + deviceId);
    }

    /**
     * @return true if the device is still registered and was touched; false on a
     * 404 (revoked elsewhere, or never registered) — caller should fall back to
     * {@link #addDevice}. Any other failure (network, 5xx) propagates so a
     * transient outage doesn't get misread as "please re-register".
     */
    public boolean touchDevice(long deviceId) throws ApiException, IOException {
        try {
            post("api/v1/user/devices/" + deviceId + "/touch", new JsonObject(), JsonObject.class, true);
            return true;
        } catch (ApiException e) {
            if (e.httpCode == 404) return false;
            throw e;
        }
    }

    public SubscriptionLinksResponse getSubscriptionLinks() throws ApiException, IOException {
        return rememberPingTargets(get("api/v1/user/subscription/links", SubscriptionLinksResponse.class));
    }

    /**
     * @param region optional (from {@link #getRegions()}) — restricts the returned
     *   links to that region's online nodes; the server falls back to every online
     *   node (today's "auto" behavior) if the region currently has none, reported
     *   back via {@link SubscriptionLinksResponse#requestedRegionAvailable}.
     */
    public SubscriptionLinksResponse getSubscriptionLinks(String region) throws ApiException, IOException {
        if (region == null || region.isBlank()) {
            return getSubscriptionLinks();
        }
        return rememberPingTargets(executeWithHostRotation(host -> {
            HttpUrl.Builder url = HttpUrl.parse(host + "api/v1/user/subscription/links").newBuilder();
            url.addQueryParameter("region", region);
            Request.Builder builder = new Request.Builder().url(url.build()).get();
            applyAuth(builder);
            return builder.build();
        }, SubscriptionLinksResponse.class));
    }

    /**
     * Notes one node per region from links that were fetched anyway (every
     * connect fetches them), so measuring a region's latency does not need a
     * links request of its own — which also counts against the account's
     * anti-enumeration budget server-side.
     */
    private SubscriptionLinksResponse rememberPingTargets(SubscriptionLinksResponse resp) {
        if (resp == null || resp.links == null || tokenStore == null) return resp;
        long now = System.currentTimeMillis();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String link : resp.links) {
            try {
                VlessUri uri = VlessUri.parse(link);
                String region = uri.getRegionLabel();
                if (region != null && seen.add(region)) {
                    tokenStore.savePingTarget(region, uri.getHost(), uri.getPort(), now);
                }
            } catch (Exception ignored) {
                // a malformed link just isn't remembered
            }
        }
        return resp;
    }

    /**
     * The public tariff catalogue. The profile endpoint only carries the
     * subscription's {@code tariffId}, so this is what lets the app name the
     * user's plan, and say what it costs and how many devices it allows,
     * rather than showing an internal id or nothing at all.
     */
    public List<TariffInfo> getTariffs() throws ApiException, IOException {
        TariffInfo[] tariffs = get("api/v1/user/tariffs", TariffInfo[].class);
        return tariffs != null ? List.of(tariffs) : List.of();
    }

    /**
     * Relay peers this account can connect *through* right now. A relay is a
     * path to a node, not an exit — see p2p/P2pRelayConnector.
     */
    public List<RelayInfo> getP2pRelays() throws ApiException, IOException {
        RelaysResponse resp = get("api/v1/user/p2p/relays", RelaysResponse.class);
        return resp != null && resp.relays != null ? resp.relays : List.of();
    }

    /**
     * Peers this account may use as an *exit* in that region — the traffic
     * leaves for the internet from their device, under their IP. This is what
     * the user picked when they chose a P2P row in the region list.
     *
     * Empty on a trial plan: P2P exits are a paid-plan feature and the row is
     * shown locked there, so "nobody available" is the honest answer rather
     * than an error.
     */
    public List<RelayInfo> getP2pExits(String region) throws ApiException, IOException {
        ExitsResponse resp = get(
                "api/v1/user/p2p/exits?region=" + java.net.URLEncoder.encode(region, "UTF-8"),
                ExitsResponse.class);
        return resp != null && resp.exits != null ? resp.exits : List.of();
    }

    /** Hands one signaling payload to a relay; its replies are collected by pollP2pSignal. */
    public void sendP2pSignal(long relayNodeId, String sessionId, byte[] payload) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("sessionId", sessionId);
        body.addProperty("payloadBase64", android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP));
        post("api/v1/user/p2p/nodes/" + relayNodeId + "/signal", body, JsonObject.class, true);
    }

    /** The relay's next signal, or null when none arrived within the wait — an ordinary outcome while negotiating. */
    public byte[] pollP2pSignal(String sessionId, long waitMs) throws ApiException, IOException {
        JsonObject resp = get("api/v1/user/p2p/sessions/" + sessionId + "/signals?waitMs=" + waitMs, JsonObject.class);
        if (resp == null || !resp.has("payloadBase64") || resp.get("payloadBase64").isJsonNull()) {
            return null;
        }
        return android.util.Base64.decode(resp.get("payloadBase64").getAsString(), android.util.Base64.DEFAULT);
    }

    /** The client half of the dual traffic report that pays the relay's owner. */
    public void reportP2pSessionTraffic(String sessionId, long relayNodeId, long bytesRelayed, boolean exit) {
        JsonObject body = new JsonObject();
        body.addProperty("nodeId", relayNodeId);
        body.addProperty("bytesRelayed", bytesRelayed);
        // Exit sessions touch no node of ours, so this flag is the only thing
        // that puts their bytes on this account's quota server-side.
        body.addProperty("exit", exit);
        try {
            post("api/v1/user/p2p/sessions/" + sessionId + "/traffic-report", body, JsonObject.class, true);
        } catch (Exception ignored) {
            // Best-effort: the relay's own half is what actually credits it.
        }
    }

    public void closeP2pSession(String sessionId) {
        try {
            delete("api/v1/user/p2p/sessions/" + sessionId);
        } catch (Exception ignored) {
            // The broker forgets idle sessions on its own.
        }
    }

    /** Regions with at least one online node this user's subscription can reach, each with a rough load indicator. */
    public List<RegionInfo> getRegions() throws ApiException, IOException {
        RegionsResponse resp = get("api/v1/user/regions", RegionsResponse.class);
        return resp.regions != null ? resp.regions : List.of();
    }

    /**
     * Latency to the one region the user actually picked, or -1 if it cannot
     * be measured (no selection, no link, unreachable).
     *
     * This used to measure every region on every Connect-screen load. A "ping"
     * here is a real TCP connection to a node's live Xray inbound, so pinging
     * the whole list cost one connection per region per screen open — on
     * Android that meant a fresh round on every bottom-nav switch — and those
     * connections land in the same activeConnections the load indicator is
     * computed from, i.e. measuring load also nudged it. The repo owner's
     * call: measure only the chosen region, where a number is actually acted
     * on, and let the list compare regions by load and node count instead.
     */
    public int pingSelectedRegion(String region) {
        if (region == null || region.isBlank()) {
            return -1;
        }
        // A P2P exit has nothing to measure this way: a peer is reached over
        // WebRTC and its address is deliberately never handed out.
        if (com.vpn.android.util.RegionKey.isP2p(region)) {
            return -1;
        }
        try {
            String[] target = tokenStore != null
                    ? tokenStore.getPingTarget(region, System.currentTimeMillis(), PING_TARGET_MAX_AGE_MS)
                    : null;
            if (target == null) {
                // Not learnt yet (never connected there): fetch once, which
                // also remembers it for next time.
                SubscriptionLinksResponse resp = getSubscriptionLinks(region);
                VlessUri node = firstLinkForRegion(resp == null ? null : resp.links, region);
                if (node == null) return -1;
                target = new String[]{node.getHost(), String.valueOf(node.getPort())};
            }
            return measureTcpLatency(target[0], Integer.parseInt(target[1]), 2000);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * The first link that actually belongs to {@code region}, or null if none
     * does. The server falls back to any online node when the asked-for region
     * has none ({@code requestedRegionAvailable: false}), so taking whatever
     * came back would report a node in another country as this region's
     * latency. Malformed links are skipped, not thrown on — one bad entry
     * should not cost the measurement.
     */
    static VlessUri firstLinkForRegion(List<String> links, String region) {
        if (links == null) {
            return null;
        }
        for (String link : links) {
            try {
                VlessUri uri = VlessUri.parse(link);
                if (region.equals(uri.getRegionLabel())) {
                    return uri;
                }
            } catch (Exception ignored) {
                // skip a malformed link
            }
        }
        return null;
    }

    public static int measureTcpLatency(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return (int) (System.currentTimeMillis() - start);
        } catch (Exception e) {
            return -1;
        }
    }

    public RoutingConfigResponse getRoutingConfig(String operator, String region) throws ApiException, IOException {

        return executeWithHostRotation(host -> {
            HttpUrl.Builder url = HttpUrl.parse(host + "api/v1/client/config").newBuilder();
            if (operator != null) url.addQueryParameter("operator", operator);
            if (region != null) url.addQueryParameter("region", region);
            Request.Builder builder = new Request.Builder().url(url.build()).get();
            applyAuth(builder);
            return builder.build();
        }, RoutingConfigResponse.class);
    }

    /**
     * Ships collected client failures (see DiagnosticsReporter). Sent without
     * requiring a token: "cannot authenticate" is one of the failures worth
     * hearing about, and the server bounds this channel itself rather than
     * trusting the caller.
     */
    public void submitDiagnostics(String source, String appVersion, String reporterId,
                                  java.util.List<com.vpn.android.diagnostics.DiagnosticsBuffer.Event> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("source", source);
        body.addProperty("appVersion", appVersion);
        body.addProperty("reporterId", reporterId);

        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (com.vpn.android.diagnostics.DiagnosticsBuffer.Event event : events) {
            JsonObject item = new JsonObject();
            item.addProperty("severity", event.severity);
            item.addProperty("component", event.component);
            item.addProperty("code", event.code);
            item.addProperty("message", event.message);
            if (event.detail != null) item.addProperty("detail", event.detail);
            if (event.context != null && !event.context.isEmpty()) {
                JsonObject context = new JsonObject();
                for (java.util.Map.Entry<String, String> entry : event.context.entrySet()) {
                    context.addProperty(entry.getKey(), entry.getValue());
                }
                item.add("context", context);
            }
            array.add(item);
        }
        body.add("events", array);

        try {
            post("api/v1/client/diagnostics", body, JsonObject.class, false);
        } catch (Exception ignored) {
            // Best-effort by design — see DiagnosticsReporter#flush.
        }
    }

    public void submitTelemetry(Long nodeId, String operator, String region, String transport,
                                 int connectTimeMs, int failureCount, boolean whitelistSuspected) {
        JsonObject body = new JsonObject();
        if (nodeId != null) body.addProperty("nodeId", nodeId);
        body.addProperty("operator", operator);
        body.addProperty("region", region);
        body.addProperty("transport", transport);
        body.addProperty("connectTimeMs", connectTimeMs);
        body.addProperty("failureCount", failureCount);
        body.addProperty("isWhitelistSuspected", whitelistSuspected);
        try {
            post("api/v1/client/telemetry", body, JsonObject.class, true);
        } catch (Exception ignored) {
            // Telemetry is best-effort; never let it break the connection flow.
        }
    }

    /**
     * Mints a short-lived, single-use exchange code the web dashboard can
     * redeem for a full-privilege web session (see WEB_HANDOFF_RESEARCH.md)
     * — lets a client that already holds a valid JWT send the user to the
     * web dashboard (e.g. to manage billing) without asking them to log in
     * again. {@code webUrl} in the response is the dashboard's base origin;
     * callers should not hardcode it independently.
     */
    public WebHandoffResponse requestWebHandoff() throws ApiException, IOException {
        return post("api/v1/auth/web-handoff", new JsonObject(), WebHandoffResponse.class, true);
    }

    /**
     * Best-effort revokes this install's own registered Device row before
     * wiping the local session — otherwise it lingers in the user's device
     * list (and against their device-limit count) until the 30-day
     * inactivity window ages it out on its own (see
     * DeviceManagementService#DEVICE_ACTIVE_WINDOW_DAYS on the server). Does
     * a blocking network call — callers must invoke this off the main
     * thread (see ProfileFragment#logout's Async.run usage). A failed
     * revoke (offline, already gone) must not block logout itself.
     */
    public void logout() {
        long deviceId = tokenStore.getDeviceId();
        if (deviceId != -1) {
            try {
                deleteDevice(deviceId);
            } catch (ApiException | IOException e) {
                // Best-effort — proceed with clearing the local session regardless.
            }
        }
        tokenStore.clear();
    }

    // --- P2P relay (docs/research/P2P_RELAY_FEASIBILITY.md §8) ------------------

    public Instant acceptP2pTerms() throws ApiException, IOException {
        JsonObject resp = post("api/v1/user/p2p/accept-terms", new JsonObject(), JsonObject.class, true);
        return Instant.parse(resp.get("acceptedAt").getAsString());
    }

    public P2pStatusResponse getP2pStatus() throws ApiException, IOException {
        return get("api/v1/user/p2p/status", P2pStatusResponse.class);
    }

    /** @return a fresh bootstrap token bound to this user (docs §8.4) — consumed immediately by P2pRelayAgent's RegisterNode call, never stockpiled. */
    public String createP2pBootstrapToken() throws ApiException, IOException {
        JsonObject resp = post("api/v1/user/p2p/bootstrap-token", new JsonObject(), JsonObject.class, true);
        return resp.get("token").getAsString();
    }

    // --- internal HTTP helpers -------------------------------------------------

    /** Builds a Request against a given (already-normalized, trailing-slash) host. */
    private interface RequestFactory {
        Request build(String host);
    }

    private <T> T get(String path, Class<T> type) throws ApiException, IOException {
        return executeWithHostRotation(host -> {
            Request.Builder builder = new Request.Builder().url(host + path).get();
            applyAuth(builder);
            return builder.build();
        }, type);
    }

    private <T> T post(String path, JsonObject body, Class<T> type, boolean auth) throws ApiException, IOException {
        String json = gson.toJson(body);
        return executeWithHostRotation(host -> {
            Request.Builder builder = new Request.Builder()
                    .url(host + path)
                    .post(RequestBody.create(json, JSON));
            if (auth) applyAuth(builder);
            return builder.build();
        }, type);
    }

    private void delete(String path) throws ApiException, IOException {
        executeWithHostRotation(host -> {
            Request.Builder builder = new Request.Builder().url(host + path).delete();
            applyAuth(builder);
            return builder.build();
        }, JsonObject.class);
    }

    private void applyAuth(Request.Builder builder) {
        String token = tokenStore.getToken();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    /**
     * Phase 10 hardening: on a network-level failure (DNS/connect/timeout —
     * consistent with the primary API domain being blocked or poisoned),
     * retries against the next configured backup domain (see ApiHostRotation)
     * before giving up. A successful HTTP response, even an error one (4xx/5xx),
     * is never retried against another host — that's a real answer from the
     * real server, not a connectivity problem.
     */
    private <T> T executeWithHostRotation(RequestFactory factory, Class<T> type) throws ApiException, IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < hostRotation.size(); attempt++) {
            String host = attempt == 0 ? hostRotation.current() : hostRotation.advance();
            try {
                return execute(factory.build(host), type);
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError;
    }

    /**
     * Sends one request, keeping the session alive around it: a token close
     * to expiry is renewed first, and a 401 (expired or revoked) is answered
     * by signing a device-trial account straight back in and retrying once.
     * Anyone else has to sign in themselves — the token is dropped and the
     * SessionExpiredListener told, instead of every later call failing with
     * an error nobody explains.
     */
    private <T> T execute(Request request, Class<T> type) throws ApiException, IOException {
        boolean authed = tokenStore != null && request.header("Authorization") != null;
        if (!authed) {
            return executeOnce(request, type);
        }
        request = renewIfNearExpiry(request);
        try {
            return executeOnce(request, type);
        } catch (ApiException e) {
            if (e.httpCode != 401) throw e;
            String renewed = recoverSession(request.header("Authorization"));
            if (renewed == null) throw e;
            return executeOnce(request.newBuilder().header("Authorization", "Bearer " + renewed).build(), type);
        }
    }

    private Request renewIfNearExpiry(Request request) {
        String token = tokenStore.getToken();
        long exp = JwtExpiry.expiresAtEpochSec(token);
        long nowMs = System.currentTimeMillis();
        long leftSec = exp - nowMs / 1000;
        // Already expired: /refresh would only answer 401 — the 401 path in
        // execute() is what handles that.
        if (exp <= 0 || leftSec <= 0 || leftSec > REFRESH_WHEN_LEFT_SEC
                || nowMs - lastRefreshAttemptMs < REFRESH_ATTEMPT_INTERVAL_MS) {
            return request;
        }
        synchronized (SESSION_LOCK) {
            if (!token.equals(tokenStore.getToken())) {
                // Someone else renewed it while we waited.
                return request.newBuilder().header("Authorization", "Bearer " + tokenStore.getToken()).build();
            }
            lastRefreshAttemptMs = nowMs;
            try {
                Request refresh = new Request.Builder()
                        .url(hostRotation.current() + "api/v1/auth/refresh")
                        .header("Authorization", "Bearer " + token)
                        .post(RequestBody.create("{}", JSON))
                        .build();
                AuthResponse resp = executeOnce(refresh, AuthResponse.class);
                if (resp != null && resp.token != null) {
                    tokenStore.replaceToken(resp.token);
                    return request.newBuilder().header("Authorization", "Bearer " + resp.token).build();
                }
            } catch (Exception ignored) {
                // Best-effort: the current token still works for now.
            }
        }
        return request;
    }

    /** @return a working token to retry with, or null when the user has to sign in. */
    private String recoverSession(String rejectedAuthHeader) {
        synchronized (SESSION_LOCK) {
            String current = tokenStore.getToken();
            if (current != null && !("Bearer " + current).equals(rejectedAuthHeader)) {
                return current; // already recovered by another call
            }
            if (Boolean.TRUE.equals(tokenStore.isDeviceAccount())) {
                try {
                    return deviceAuth(tokenStore.getOrCreateDeviceUuid(), null).token;
                } catch (Exception ignored) {
                    // e.g. the device now belongs to a registered account — fall through
                }
            }
            tokenStore.clearToken();
        }
        SessionExpiredListener listener = sessionExpiredListener;
        if (listener != null) listener.onSessionExpired();
        return null;
    }

    private <T> T executeOnce(Request request, Class<T> type) throws ApiException, IOException {
        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String message = extractError(responseBody);
                throw new ApiException(response.code(), message);
            }
            if (responseBody.isEmpty()) {
                return null;
            }
            return gson.fromJson(responseBody, type);
        }
    }

    private String extractError(String responseBody) {
        try {
            JsonObject obj = gson.fromJson(responseBody, JsonObject.class);
            if (obj != null && obj.has("error")) {
                return obj.get("error").getAsString();
            }
        } catch (Exception ignored) {
            // not JSON, fall through
        }
        return responseBody.isEmpty() ? "Request failed" : responseBody;
    }
}
