package com.vpn.android.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vpn.android.api.model.AuthResponse;
import com.vpn.android.api.model.UserProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration tests for Android guest profile UX parity
 * (docs/TODO_ANDROID_GUEST_PROFILE.md):
 *
 * 1. Anonymous trial login via deviceAuth
 * 2. Profile fetch parsing isGuest field
 * 3. Guest upgrade in place via POST /api/v1/auth/upgrade
 * 4. Existing account login attaching deviceUuid for server-side guest merge
 * 5. Google auth attaching deviceUuid for server-side guest merge
 * 6. TokenStore preservation of deviceUuid across clear()
 */
public class GuestProfileIntegrationTest {

    private TestHttpServer server;
    private TokenStore tokenStore;
    private ApiClient apiClient;

    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();

    private boolean guestUpgraded = false;

    @Before
    public void setUp() throws IOException {
        server = new TestHttpServer();

        server.register("POST", "/api/v1/auth/device", request -> {
            recordRequest(request);
            return new TestHttpServer.Response(200, "{\"token\":\"guest-jwt-token-123\",\"userId\":101}");
        });

        server.register("GET", "/api/v1/user/profile", request -> {
            recordRequest(request);
            String auth = request.headers.get("authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                return new TestHttpServer.Response(401, "{\"message\":\"Unauthorized\"}");
            }
            String email = guestUpgraded ? "upgraded@example.com" : "guest_101@device.vpn";
            boolean isGuest = !guestUpgraded;
            String json = "{"
                    + "\"id\":101,"
                    + "\"email\":\"" + email + "\","
                    + "\"role\":\"USER\","
                    + "\"balanceUsdtMicro\":0,"
                    + "\"referralCode\":\"REF101\","
                    + "\"hasActiveSubscription\":true,"
                    + "\"isGuest\":" + isGuest + ","
                    + "\"subscription\":{"
                    + "  \"id\":201,"
                    + "  \"tariffId\":\"trial\","
                    + "  \"trafficUsedBytes\":1048576,"
                    + "  \"trafficLimitBytes\":10737418240,"
                    + "  \"expiresAt\":\"2026-09-14T21:00:00Z\""
                    + "}"
                    + "}";
            return new TestHttpServer.Response(200, json);
        });

        server.register("POST", "/api/v1/auth/upgrade", request -> {
            recordRequest(request);
            String auth = request.headers.get("authorization");
            if (auth == null || !auth.equals("Bearer guest-jwt-token-123")) {
                return new TestHttpServer.Response(401, "{\"message\":\"Unauthorized upgrade\"}");
            }
            JsonObject body = JsonParser.parseString(request.body).getAsJsonObject();
            if (!body.has("email") || !body.has("password")) {
                return new TestHttpServer.Response(400, "{\"message\":\"Missing credentials\"}");
            }
            guestUpgraded = true;
            return new TestHttpServer.Response(200, "{\"token\":\"upgraded-jwt-token-456\",\"userId\":101}");
        });

        server.register("POST", "/api/v1/auth/login", request -> {
            recordRequest(request);
            return new TestHttpServer.Response(200, "{\"token\":\"user-login-jwt-789\",\"userId\":500}");
        });

        server.register("POST", "/api/v1/auth/google", request -> {
            recordRequest(request);
            return new TestHttpServer.Response(200, "{\"token\":\"google-login-jwt-999\",\"userId\":600}");
        });

        FakeSharedPreferences prefs = new FakeSharedPreferences();
        tokenStore = new TokenStore(prefs);

        OkHttpClient directHttp = new OkHttpClient.Builder().build();
        apiClient = new ApiClient(List.of(server.getBaseUrl()), tokenStore, directHttp);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private void recordRequest(TestHttpServer.Request request) {
        lastRequestPath.set(request.path);
        lastAuthHeader.set(request.headers.get("authorization"));
        lastRequestBody.set(request.body);
    }

    @Test
    public void testFullGuestProfileAndUpgradeFlow() throws Exception {
        // 1. Initial launch: deviceUuid is generated and used for anonymous trial login
        String deviceUuid = tokenStore.getOrCreateDeviceUuid();
        assertNotNull(deviceUuid);
        assertFalse(deviceUuid.isBlank());

        AuthResponse deviceAuthResp = apiClient.deviceAuth(deviceUuid, null);
        assertEquals("guest-jwt-token-123", deviceAuthResp.token);
        assertEquals(101L, deviceAuthResp.userId);
        assertEquals("guest-jwt-token-123", tokenStore.getToken());
        assertTrue(tokenStore.isLoggedIn());

        // 2. Fetch profile: isGuest is true on the guest account
        UserProfile guestProfile = apiClient.getProfile();
        assertNotNull(guestProfile);
        assertTrue("Guest profile must have isGuest=true", guestProfile.isGuest);
        assertEquals("guest_101@device.vpn", guestProfile.email);
        assertTrue(guestProfile.hasActiveSubscription);
        assertEquals("Bearer guest-jwt-token-123", lastAuthHeader.get());

        // 3. User taps "Sign in or register" and registers -> triggers upgradeGuest()
        AuthResponse upgradeResp = apiClient.upgradeGuest("upgraded@example.com", "Password123!");
        assertEquals("upgraded-jwt-token-456", upgradeResp.token);
        assertEquals(101L, upgradeResp.userId);
        assertEquals("upgraded-jwt-token-456", tokenStore.getToken());

        // Verify upgrade request contract
        assertEquals("/api/v1/auth/upgrade", lastRequestPath.get());
        assertEquals("Bearer guest-jwt-token-123", lastAuthHeader.get());
        JsonObject upgradeBody = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject();
        assertEquals("upgraded@example.com", upgradeBody.get("email").getAsString());
        assertEquals("Password123!", upgradeBody.get("password").getAsString());

        // Subsequent getProfile() now reports isGuest=false
        UserProfile upgradedProfile = apiClient.getProfile();
        assertFalse("Upgraded profile must have isGuest=false", upgradedProfile.isGuest);
        assertEquals("upgraded@example.com", upgradedProfile.email);
        assertEquals("Bearer upgraded-jwt-token-456", lastAuthHeader.get());
    }

    @Test
    public void testLoginAttachesDeviceUuidForGuestMerge() throws Exception {
        String deviceUuid = tokenStore.getOrCreateDeviceUuid();

        AuthResponse loginResp = apiClient.login("existing@example.com", "MyPassword123");
        assertEquals("user-login-jwt-789", loginResp.token);
        assertEquals(500L, loginResp.userId);

        assertEquals("/api/v1/auth/login", lastRequestPath.get());
        JsonObject loginBody = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject();
        assertEquals("existing@example.com", loginBody.get("email").getAsString());
        assertEquals("MyPassword123", loginBody.get("password").getAsString());
        // Verify deviceUuid is attached to trigger backend GuestMergeService
        assertEquals("login request must include deviceUuid", deviceUuid, loginBody.get("deviceUuid").getAsString());
    }

    @Test
    public void testGoogleAuthAttachesDeviceUuidForGuestMerge() throws Exception {
        String deviceUuid = tokenStore.getOrCreateDeviceUuid();

        AuthResponse googleResp = apiClient.googleAuth("google-oauth-token-xyz", "REF999");
        assertEquals("google-login-jwt-999", googleResp.token);
        assertEquals(600L, googleResp.userId);

        assertEquals("/api/v1/auth/google", lastRequestPath.get());
        JsonObject googleBody = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject();
        assertEquals("google-oauth-token-xyz", googleBody.get("idToken").getAsString());
        assertEquals("REF999", googleBody.get("referralCode").getAsString());
        // Verify deviceUuid is attached to trigger backend GuestMergeService
        assertEquals("google auth request must include deviceUuid", deviceUuid, googleBody.get("deviceUuid").getAsString());
    }

    @Test
    public void testLogoutPreservesDeviceUuid() throws Exception {
        String deviceUuidBefore = tokenStore.getOrCreateDeviceUuid();
        tokenStore.save("active-jwt", 123L);
        tokenStore.saveSelectedRegion("de");

        tokenStore.clear();

        assertFalse(tokenStore.isLoggedIn());
        assertEquals("deviceUuid must survive logout", deviceUuidBefore, tokenStore.getOrCreateDeviceUuid());
        assertEquals("selectedRegion must survive logout", "de", tokenStore.getSelectedRegion());
    }
}
