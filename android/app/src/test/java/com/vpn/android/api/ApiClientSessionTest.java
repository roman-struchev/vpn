package com.vpn.android.api;

import com.vpn.android.api.model.UserProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The client half of "the app quietly stops working after 30 days": a token
 * close to expiry is renewed before use, and a 401 either signs a
 * device-trial account straight back in or ends the session visibly.
 */
public class ApiClientSessionTest {

    private static final String PROFILE_JSON = "{\"id\":1,\"email\":\"a@b.c\",\"hasActiveSubscription\":false,\"isGuest\":true}";

    private TestHttpServer server;
    private TokenStore tokenStore;
    private ApiClient apiClient;
    private final AtomicInteger deviceAuthCalls = new AtomicInteger();
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private final AtomicInteger linksCalls = new AtomicInteger();
    private volatile String acceptedToken;

    static String jwtExpiringIn(long seconds) {
        long exp = System.currentTimeMillis() / 1000 + seconds;
        String payload = "{\"sub\":\"1\",\"exp\":" + exp + ",\"n\":" + System.nanoTime() + "}";
        return "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".s";
    }

    @Before
    public void setUp() throws IOException {
        server = new TestHttpServer();
        server.register("GET", "/api/v1/user/profile", req -> {
            String auth = req.headers.get("authorization");
            if (auth == null || !auth.equals("Bearer " + acceptedToken)) {
                return new TestHttpServer.Response(401, "{\"error\":\"Unauthorized\"}");
            }
            return new TestHttpServer.Response(200, PROFILE_JSON);
        });
        server.register("POST", "/api/v1/auth/device", req -> {
            deviceAuthCalls.incrementAndGet();
            acceptedToken = jwtExpiringIn(30L * 24 * 3600);
            return new TestHttpServer.Response(200, "{\"token\":\"" + acceptedToken + "\",\"userId\":1}");
        });
        server.register("POST", "/api/v1/auth/refresh", req -> {
            refreshCalls.incrementAndGet();
            String auth = req.headers.get("authorization");
            if (auth == null || !auth.equals("Bearer " + acceptedToken)) {
                return new TestHttpServer.Response(401, "{\"error\":\"Unauthorized\"}");
            }
            acceptedToken = jwtExpiringIn(30L * 24 * 3600);
            return new TestHttpServer.Response(200, "{\"token\":\"" + acceptedToken + "\",\"userId\":1}");
        });
        server.register("GET", "/api/v1/user/subscription/links", req -> {
            linksCalls.incrementAndGet();
            return new TestHttpServer.Response(200,
                    "{\"links\":[\"vless://11111111-1111-1111-1111-111111111111@127.0.0.1:"
                            + server.getPort() + "?security=reality&type=xhttp#Netherlands\"]}");
        });
        tokenStore = new TokenStore(new FakeSharedPreferences());
        apiClient = new ApiClient(List.of(server.getBaseUrl()), tokenStore, new OkHttpClient());
        ApiClient.resetSessionStateForTests();
    }

    @After
    public void tearDown() throws IOException {
        ApiClient.setSessionExpiredListener(null);
        server.close();
    }

    @Test
    public void expiredDeviceAccountIsSignedBackInAndTheCallRetried() throws Exception {
        tokenStore.saveSession(jwtExpiringIn(-10), 1, true);

        UserProfile profile = apiClient.getProfile();

        assertNotNull(profile);
        assertEquals(1, deviceAuthCalls.get());
        assertEquals(acceptedToken, tokenStore.getToken());
    }

    @Test
    public void expiredRegisteredAccountEndsTheSessionVisibly() throws Exception {
        tokenStore.saveSession(jwtExpiringIn(-10), 1, false);
        AtomicInteger expiredEvents = new AtomicInteger();
        ApiClient.setSessionExpiredListener(expiredEvents::incrementAndGet);

        try {
            apiClient.getProfile();
            fail("a dead session must not look like success");
        } catch (ApiException e) {
            assertEquals(401, e.httpCode);
        }

        assertEquals("must never create a guest account behind a registered user's back", 0, deviceAuthCalls.get());
        assertNull(tokenStore.getToken());
        assertEquals(1, expiredEvents.get());
        assertEquals("the install's identity survives", tokenStore.getOrCreateDeviceUuid(), tokenStore.getOrCreateDeviceUuid());
    }

    @Test
    public void tokenCloseToExpiryIsRenewedBeforeUse() throws Exception {
        String old = jwtExpiringIn(2L * 24 * 3600);
        acceptedToken = old;
        tokenStore.saveSession(old, 1, false);

        apiClient.getProfile();

        assertEquals(1, refreshCalls.get());
        assertTrue(!old.equals(tokenStore.getToken()));
        assertEquals(acceptedToken, tokenStore.getToken());
    }

    @Test
    public void tokenWithPlentyLeftIsNotRenewed() throws Exception {
        acceptedToken = jwtExpiringIn(20L * 24 * 3600);
        tokenStore.saveSession(acceptedToken, 1, false);

        apiClient.getProfile();

        assertEquals(0, refreshCalls.get());
    }

    @Test
    public void pingReusesTheNodeLearntFromEarlierLinks() throws Exception {
        acceptedToken = jwtExpiringIn(20L * 24 * 3600);
        tokenStore.saveSession(acceptedToken, 1, false);

        apiClient.getSubscriptionLinks();
        assertEquals(1, linksCalls.get());

        int ms = apiClient.pingSelectedRegion("Netherlands");

        assertTrue("the test server itself is the node, so it is reachable", ms >= 0);
        assertEquals("no second links request just to find a host", 1, linksCalls.get());
    }
}
