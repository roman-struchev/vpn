package com.vpn.android.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vpn.android.BuildConfig;
import com.vpn.android.api.model.AuthResponse;
import com.vpn.android.api.model.DeviceDto;
import com.vpn.android.api.model.RoutingConfigResponse;
import com.vpn.android.api.model.SubscriptionLinksResponse;
import com.vpn.android.api.model.UserProfile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private final String baseUrl;
    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final TokenStore tokenStore;

    public ApiClient(TokenStore tokenStore) {
        this(BuildConfig.API_BASE_URL, tokenStore);
    }

    public ApiClient(String baseUrl, TokenStore tokenStore) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.tokenStore = tokenStore;

        OkHttpClient bootstrap = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();
        this.http = bootstrap.newBuilder()
                .dns(DohDns.create(bootstrap))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public AuthResponse login(String email, String password) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        AuthResponse resp = post("api/v1/auth/login", body, AuthResponse.class, false);
        tokenStore.save(resp.token, resp.userId);
        return resp;
    }

    public AuthResponse register(String email, String password, String referralCode) throws ApiException, IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        if (referralCode != null) body.addProperty("referralCode", referralCode);
        AuthResponse resp = post("api/v1/auth/register", body, AuthResponse.class, false);
        tokenStore.save(resp.token, resp.userId);
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

    public SubscriptionLinksResponse getSubscriptionLinks() throws ApiException, IOException {
        return get("api/v1/user/subscription/links", SubscriptionLinksResponse.class);
    }

    public RoutingConfigResponse getRoutingConfig(String operator, String region) throws ApiException, IOException {
        HttpUrl.Builder url = HttpUrl.parse(baseUrl + "api/v1/client/config").newBuilder();
        if (operator != null) url.addQueryParameter("operator", operator);
        if (region != null) url.addQueryParameter("region", region);
        return executeGet(url.build(), RoutingConfigResponse.class);
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

    public void logout() {
        tokenStore.clear();
    }

    // --- internal HTTP helpers -------------------------------------------------

    private <T> T get(String path, Class<T> type) throws ApiException, IOException {
        return executeGet(HttpUrl.parse(baseUrl + path), type);
    }

    private <T> T executeGet(HttpUrl url, Class<T> type) throws ApiException, IOException {
        Request.Builder builder = new Request.Builder().url(url).get();
        applyAuth(builder);
        return execute(builder.build(), type);
    }

    private <T> T post(String path, JsonObject body, Class<T> type, boolean auth) throws ApiException, IOException {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(gson.toJson(body), JSON));
        if (auth) applyAuth(builder);
        return execute(builder.build(), type);
    }

    private void delete(String path) throws ApiException, IOException {
        Request.Builder builder = new Request.Builder().url(baseUrl + path).delete();
        applyAuth(builder);
        execute(builder.build(), JsonObject.class);
    }

    private void applyAuth(Request.Builder builder) {
        String token = tokenStore.getToken();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
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
