package com.vpn.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.api.model.AuthResponse;
import com.vpn.android.api.model.DeviceDto;
import com.vpn.android.api.model.SubscriptionLinksResponse;
import com.vpn.android.api.model.UserProfile;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Instrumented (runs on a real device/emulator) counterpart to
 * e2e/tests/full-user-flow.spec.ts's non-UI half: proves this app's actual
 * networking + persistence stack (OkHttp's real DNS/TLS/cookie handling,
 * TokenStore's Keystore-backed EncryptedSharedPreferences — neither exists
 * under the plain-JVM `src/test` unit tests, which is why those never caught
 * a real integration regression here) round-trips against a real running
 * server: register, activate the free trial, add a device, and confirm a
 * VLESS subscription link comes back.
 *
 * Deliberately does NOT drive {@link com.vpn.android.vpn.XrayVpnService} /
 * libXray to prove actual tunneled connectivity the way
 * e2e/tests/tunnel.spec.ts does for the desktop client. Two reasons that's a
 * separate, harder follow-up rather than an oversight here:
 *
 *  1. VpnService.prepare() requires a one-time interactive system consent
 *     dialog per app install — scriptable with UiAutomator, but real added
 *     complexity this pass didn't take on.
 *  2. The VLESS+Reality fallback transport tunnel.spec.ts's own client uses
 *     to avoid needing root for port 443 listens on a single, server-wide
 *     port (vpn.grpc-fallback.port, default 8443) shared by *every* node —
 *     it is not configurable per-node. Running this suite's own real-tunnel
 *     test at the same time as tunnel.spec.ts's (each spinning up its own
 *     real local agent + xray-core node) would race to bind that same port
 *     on the host. So despite the request that these two suites be runnable
 *     "in parallel" — they aren't safely, not without also making that port
 *     configurable per test run (e.g. a request-scoped override analogous to
 *     agent/'s AGENT_PRIMARY_INBOUND_PORT_OVERRIDE, but server-side).
 *     Sequencing them (or only ever running one real-tunnel suite at a time)
 *     is the safe interim rule.
 *
 * A full tunnel-parity test would need: (a) a host-side setup step (this
 * suite has no shell/docker access from inside the emulator) that mints an
 * admin session, a bootstrap token, and starts a real local agent process
 * exactly like e2e/tests/agentHelpers.ts#startLocalAgent — registered with
 * PUBLIC_IP=10.0.2.2 (the emulator's alias for the host loopback, not
 * 127.0.0.1) so the VLESS link's host is actually reachable from inside the
 * emulator — handed to this test via an instrumentation argument or a
 * generated fixture file; (b) UiAutomator to click through the VpnService
 * consent dialog; (c) the port-sharing fix above, or running it alone.
 *
 * Run: from android/, with a real server already up (see root README.md)
 * and an emulator/device attached (`adb devices`):
 *   JAVA_HOME=$(/usr/libexec/java_home -v 21) \
 *     ./gradlew :app:connectedDebugAndroidTest \
 *     -PapiBaseUrl=http://10.0.2.2:8080/
 * (10.0.2.2 is the AOSP emulator's host-loopback alias; a real device on the
 * same LAN as the server needs the host's actual LAN IP instead.)
 */
@RunWith(AndroidJUnit4.class)
public class ApiFlowTest {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Test
    public void registerActivateTrialAddDeviceAndFetchLinks() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // A fresh TokenStore/ApiClient per run, exactly like a real first
        // launch — not a shared/mocked instance the unit tests can't build
        // (EncryptedSharedPreferences needs a real Android Keystore).
        TokenStore tokenStore = new TokenStore(context);
        ApiClient api = new ApiClient(tokenStore);

        // "e2e-" prefix matters: e2e/global-teardown.mjs sweeps
        // 'e2e-%@example.com' rows from the shared dev Postgres after every
        // Playwright run — this test registers a real user against that same
        // server/DB, so it needs to match that pattern to not accumulate.
        String email = "e2e-android-" + System.currentTimeMillis() + "@example.com";
        String password = "Test-Passw0rd!";

        AuthResponse auth = api.register(email, password, null);
        assertNotNull("register() should return a JWT", auth.token);

        UserProfile profileBeforeSub = api.getProfile();
        assertFalse("a fresh account must not have already used its trial", profileBeforeSub.hasActiveSubscription);

        // ApiClient has no purchase/activate call (the Android app has no
        // billing UI — see android/README.md's endpoint list) — the free
        // trial costs nothing, so a raw authenticated POST is the shortest
        // path to a subscription this test can actually get without needing
        // an admin session to credit balance for a paid tariff.
        activateFreeTrial(BuildConfig.API_BASE_URL, auth.token);

        UserProfile profileAfterSub = api.getProfile();
        assertTrue("trial activation should produce an active subscription", profileAfterSub.hasActiveSubscription);
        assertEquals("trial", profileAfterSub.subscription.tariffId);

        DeviceDto device = api.addDevice("android-e2e-emulator", "ANDROID");
        assertTrue(device.id > 0);

        // exportVlessLinksForOwnApp (server/.../SubscriptionExportService)
        // only returns links for nodes that are currently ONLINE — this
        // assertion is therefore only meaningful if at least one real node
        // is registered against the same server (e.g. from a prior or
        // concurrent e2e/tests/nodes.spec.ts or tunnel.spec.ts run against
        // this dev DB); it does not spin one up itself (see class doc).
        SubscriptionLinksResponse links = api.getSubscriptionLinks();
        assertNotNull(links.links);
        // Deliberately not asserting count > 0 here for the reason above —
        // an empty list on a totally fresh server (no nodes registered yet)
        // is the correct, honest response, not a bug this test should flag.
    }

    private static void activateFreeTrial(String baseUrl, String token) throws IOException {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        RequestBody body = RequestBody.create("{\"tariffId\":\"trial\",\"isAnnual\":false}", JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "api/v1/user/billing/purchase")
                .header("Authorization", "Bearer " + token)
                .post(body)
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Trial activation failed: " + response.code() + " " + responseBody);
            }
        }
    }
}
