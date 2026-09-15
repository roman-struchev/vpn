package com.vpn.android.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vpn.android.BuildConfig;
import com.vpn.android.api.model.AuthResponse;
import com.vpn.android.api.model.DeviceDto;
import com.vpn.android.api.model.RegionInfo;
import com.vpn.android.api.model.RegionsResponse;
import com.vpn.android.api.model.RoutingConfigResponse;
import com.vpn.android.api.model.SubscriptionLinksResponse;
import com.vpn.android.api.model.UserProfile;
import com.vpn.android.api.model.WebHandoffResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    public ApiClient(TokenStore tokenStore) {
        this(hostsFromBuildConfig(), tokenStore);
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

        if (customHttp != null) {
            this.http = customHttp;
        } else {
            OkHttpClient bootstrap = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build();
            this.http = bootstrap.newBuilder()
                    .dns(DohDns.create(bootstrap))
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
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
            tokenStore.save(resp.token, resp.userId);
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
            tokenStore.save(resp.token, resp.userId);
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
            tokenStore.save(resp.token, resp.userId);
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
            tokenStore.save(resp.token, resp.userId);
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
            tokenStore.save(resp.token, resp.userId);
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
        return get("api/v1/user/subscription/links", SubscriptionLinksResponse.class);
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
        return executeWithHostRotation(host -> {
            HttpUrl.Builder url = HttpUrl.parse(host + "api/v1/user/subscription/links").newBuilder();
            url.addQueryParameter("region", region);
            Request.Builder builder = new Request.Builder().url(url.build()).get();
            applyAuth(builder);
            return builder.build();
        }, SubscriptionLinksResponse.class);
    }

    /** Regions with at least one online node this user's subscription can reach, each with a rough load indicator. */
    public List<RegionInfo> getRegions() throws ApiException, IOException {
        RegionsResponse resp = get("api/v1/user/regions", RegionsResponse.class);
        return resp.regions != null ? resp.regions : List.of();
    }

    /**
     * Measures TCP connect latency across subscription node endpoints.
     * Returns a map of region/remark to latency in milliseconds.
     */
    public Map<String, Integer> pingRegions() {
        Map<String, Integer> results = new ConcurrentHashMap<>();
        try {
            SubscriptionLinksResponse resp = getSubscriptionLinks();
            if (resp != null && resp.links != null) {
                for (String link : resp.links) {
                    try {
                        VlessUri uri = VlessUri.parse(link);
                        String key = (uri.getRemark() != null && !uri.getRemark().isBlank()) ? uri.getRemark() : uri.getHost();
                        if (!results.containsKey(key)) {
                            int latency = measureTcpLatency(uri.getHost(), uri.getPort(), 2000);
                            if (latency >= 0) {
                                results.put(key, latency);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return results;
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

    private <T> T execute(Request request, Class<T> type) throws ApiException, IOException {
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
