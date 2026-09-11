package com.vpn.android.api;

import com.vpn.android.api.model.AuthResponse;
import com.vpn.android.api.model.UserProfile;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.UUID;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Live integration test verifying the Android client against the actual
 * running Spring Boot backend (http://127.0.0.1:8080).
 *
 * If the local server is not running, the test is gracefully skipped via Assume.
 */
public class LiveServerGuestIntegrationTest {

    private static final String SERVER_BASE_URL = "http://127.0.0.1:8080";

    private TokenStore tokenStore;
    private ApiClient apiClient;

    @Before
    public void setUp() {
        Assume.assumeTrue("Backend server at " + SERVER_BASE_URL + " must be running", isServerUp());

        FakeSharedPreferences prefs = new FakeSharedPreferences();
        tokenStore = new TokenStore(prefs);

        OkHttpClient directHttp = new OkHttpClient.Builder().build();
        apiClient = new ApiClient(List.of(SERVER_BASE_URL), tokenStore, directHttp);
    }

    private boolean isServerUp() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(SERVER_BASE_URL + "/actuator/health").openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void testLiveGuestLifecycleAgainstRealBackend() throws Exception {
        String testDeviceUuid = "test-android-device-" + UUID.randomUUID();
        // Seed token store with device UUID
        tokenStore.getOrCreateDeviceUuid();
        // Replace with our unique test uuid
        tokenStore.clear();
        new FakeSharedPreferences(); // clean
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString("device_uuid", testDeviceUuid).commit();
        tokenStore = new TokenStore(prefs);
        apiClient = new ApiClient(List.of(SERVER_BASE_URL), tokenStore, new OkHttpClient.Builder().build());

        // 1. Device Auth (Guest login)
        AuthResponse deviceAuthResp = apiClient.deviceAuth(testDeviceUuid, null);
        assertNotNull(deviceAuthResp.token);
        assertTrue(deviceAuthResp.userId > 0);

        // 2. Fetch profile: verify server returns isGuest = true
        UserProfile guestProfile = apiClient.getProfile();
        assertNotNull(guestProfile);
        assertTrue("Backend must report isGuest=true for device-auth accounts", guestProfile.isGuest);
        assertTrue("Trial account must have active subscription", guestProfile.hasActiveSubscription);

        // 3. Upgrade guest account to credentialed account
        String testEmail = "android_guest_" + System.currentTimeMillis() + "@example.com";
        String testPassword = "Password123!Secure";
        AuthResponse upgradeResp = apiClient.upgradeGuest(testEmail, testPassword);
        assertNotNull(upgradeResp.token);
        assertEquals(deviceAuthResp.userId, upgradeResp.userId);

        // 4. Verify upgraded profile is no longer guest
        UserProfile upgradedProfile = apiClient.getProfile();
        assertNotNull(upgradedProfile);
        assertFalse("Upgraded account must report isGuest=false", upgradedProfile.isGuest);
        assertEquals(testEmail, upgradedProfile.email);

        // 5. Server-side security guard: upgraded deviceUuid cannot be re-authenticated anonymously via deviceAuth
        assertThrows(ApiException.class, () -> apiClient.deviceAuth(testDeviceUuid, null));

        // 6. Login with email & password attaching deviceUuid works cleanly
        tokenStore.clear();
        assertEquals("deviceUuid must survive clear()", testDeviceUuid, tokenStore.getOrCreateDeviceUuid());

        AuthResponse loginResp = apiClient.login(testEmail, testPassword);
        assertNotNull(loginResp.token);
        assertEquals(deviceAuthResp.userId, loginResp.userId);

        UserProfile loggedInProfile = apiClient.getProfile();
        assertFalse(loggedInProfile.isGuest);
        assertEquals(testEmail, loggedInProfile.email);
    }
}
