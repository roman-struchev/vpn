package com.vpn.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.test.espresso.NoMatchingRootException;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.vpn.android.api.TokenStore;
import com.vpn.android.ui.login.LoginActivity;
import com.vpn.android.vpn.VpnStatusBus;
import com.vpn.android.vpn.XrayVpnService;
import com.vpn.android.vpn.state.ConnectionState;

import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
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
 * The genuinely-real-traffic counterpart to {@link UiFlowTest}: that test
 * already drives the whole UI + real OS VPN-consent dialog, but explicitly
 * stops short of proving actual tunneled connectivity, because doing so
 * needs a real xray-core node reachable from the emulator — see its class
 * doc and {@code e2e/tests/tunnel.spec.ts} for what "real" means here.
 *
 * This test assumes that node already exists and is ONLINE *before* it
 * starts — see {@code android/scripts/real-tunnel-e2e.sh}, which:
 *   1. spins up a real {@code agent/src/index.ts} process (real xray-core
 *      binary, not simulated mode) registered with {@code PUBLIC_IP=10.0.2.2}
 *      (the AOSP emulator's alias for the host loopback — NOT 127.0.0.1,
 *      which inside the emulator means the emulator itself) and the gRPC+
 *      Reality fallback transport (port 8443) rather than the primary XHTTP
 *      inbound (port 443), since binding 443 needs root — exactly the
 *      approach e2e/tests/tunnel.spec.ts uses, for the same reason;
 *   2. flips the server's global TransportPolicy to primaryTransport=GRPC,
 *      so the app dials that real, actually-listening port on its very
 *      first attempt. This matters beyond speed: XrayInvoker.runXray() only
 *      fails if libXray itself throws — it never probes reachability — so
 *      a real node whose actual bind port doesn't match what the app is
 *      configured to dial would still settle into a false "CONNECTED"
 *      state that moves no real traffic. Forcing GRPC up front means the
 *      very first attempt targets a port the node genuinely has open.
 *
 * Any registered device on this shared dev server can see this one real
 * node once it's ONLINE (SubscriptionExportService and DynamicRoutingService
 * both list all ONLINE "paid" nodes globally, not scoped to whichever admin
 * bootstrapped it) — so this test's own fresh user/device, registered
 * entirely through real UI exactly like UiFlowTest, naturally picks it up
 * with no extra plumbing between the setup script and this test.
 *
 * Verification is the same bar e2e/tests/tunnel.spec.ts holds itself to:
 * not just "state reached CONNECTED", but a real HTTP GET that actually
 * round-trips through this device -> REALITY -> the real local node ->
 * the node's freedom outbound -> the open internet. Since Espresso-driven
 * instrumented tests run in the same OS process as the app under test (this
 * is what lets UiFlowTest/ApiFlowTest touch real app views/singletons
 * directly), and {@link XrayVpnService}'s TUN captures the device's whole
 * default route with no {@code addDisallowedApplication} exclusion, a plain
 * OkHttp request issued from this test's own thread after CONNECTED is
 * exactly such a real client request — no extra process or shell-out needed.
 *
 * Beyond "it connects", it covers what a user actually lives through
 * (docs/AUDIT.md, section 3):
 *   - a healthy tunnel stays CONNECTED across the periodic HTTPS liveness
 *     probes (a probe that wrongly fails would flap the connection);
 *   - the server revoking this device's key — xray keeps running, nothing
 *     gets through — is noticed, and the app recovers on its own by
 *     re-registering and fetching fresh links, with real traffic after;
 *   - disconnecting tears the TUN down, so the device is back online
 *     directly.
 *
 * Run: android/scripts/real-tunnel-e2e.sh (which also does the setup above
 * and invokes this class directly via
 * -Pandroid.testInstrumentationRunnerArguments.class). Do not run this
 * class directly without that setup — it will (correctly) fail to reach
 * CONNECTED, the same way UiFlowTest does today.
 */
@RunWith(AndroidJUnit4.class)
public class TunnelFlowTest {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long DEFAULT_TIMEOUT_MS = 20_000;
    /** Connect includes an end-to-end check of the tunnel before CONNECTED. */
    private static final long TUNNEL_TIMEOUT_MS = 60_000;
    /** Health checks run every 30s; two misses in a row mean dead. */
    private static final long DEAD_TUNNEL_NOTICED_MS = 110_000;
    /** Backoff, a retry, then a reload of profile + links + a fresh device. */
    private static final long RECOVERY_TIMEOUT_MS = 300_000;

    @Rule
    public GrantPermissionRule notificationPermissionRule =
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);

    // The explicit form: launched plainly, LoginActivity signs this install
    // into its own device-trial account on its own, racing the typing below.
    @Rule
    public ActivityScenarioRule<LoginActivity> activityRule =
            new ActivityScenarioRule<>(LoginActivity.createShowFormIntent(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()));

    private Context targetContext;

    @Before
    public void resetLoginState() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new TokenStore(targetContext).clear();
    }

    @Test
    public void connectsStaysUpSurvivesAKeyRevocationAndDisconnectsCleanly() throws Exception {
        // --- 1. Register through the real Login screen ----------------------
        String email = "e2e-android-tunnel-" + System.currentTimeMillis() + "@example.com";
        String password = "Test-Passw0rd!";

        onView(withId(R.id.toggleModeButton)).perform(click());
        onView(withId(R.id.emailInput)).perform(typeText(email), closeSoftKeyboard());
        onView(withId(R.id.passwordInput)).perform(typeText(password), closeSoftKeyboard());
        onView(withId(R.id.submitButton)).perform(click());

        waitFor(withId(R.id.bottomNav), DEFAULT_TIMEOUT_MS);
        waitFor(allOf(withId(R.id.trafficText), withText(R.string.state_no_subscription)), DEFAULT_TIMEOUT_MS);

        // --- 2. Activate the free trial (no billing UI in the app) ----------
        String token = new TokenStore(targetContext).getToken();
        assertNotNull("real UI registration should have persisted a JWT", token);
        activateFreeTrial(BuildConfig.API_BASE_URL, token);

        // --- 3. Connect through the real button and OS consent dialog -------
        // No "add device" step: the service registers this device itself
        // before it asks for links.
        waitFor(allOf(withId(R.id.statusText), withText(R.string.state_disconnected)), DEFAULT_TIMEOUT_MS);
        onView(withId(R.id.connectButton)).perform(click());

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 allowButton = device.wait(Until.findObject(By.res("android", "button1")), 5_000);
        if (allowButton != null) {
            allowButton.click();
        }

        try {
            waitFor(allOf(withId(R.id.statusText), withText(R.string.state_connected)), TUNNEL_TIMEOUT_MS);

            // --- 4. Real traffic through the real tunnel --------------------
            assertFetchesThroughTunnel();

            // --- 5. A healthy tunnel survives its own liveness checks -------
            Thread.sleep(70_000);
            assertEquals("a working tunnel must not be torn down by its own health checks",
                    ConnectionState.CONNECTED, VpnStatusBus.state.getValue());
            assertFetchesThroughTunnel();

            // --- 6. The server revokes this device: the node drops its key --
            // xray keeps running locally and the TUN stays up; only an
            // end-to-end probe can tell. Before the fix this stayed
            // "Protected" with no internet indefinitely.
            // Revoked from outside, the way it happens for real (another
            // device, the web dashboard): real-tunnel-e2e.sh watches logcat
            // for this line, signs in as this user and revokes every device.
            // Nothing in this process could do it anyway: the dev server is
            // at a private address the tunnel blocks, and a VPN app may not
            // bind sockets to the underlying network (EPERM). The recovery
            // below therefore also proves the service's own API client
            // really bypasses the tunnel (ProtectedSocketFactory) — it has
            // to reach 10.0.2.2 to re-register this device.
            android.util.Log.i("TunnelFlowTest", "REVOKE_DEVICES_NOW " + email + " " + password);
            waitForState(s -> s != ConnectionState.CONNECTED, DEAD_TUNNEL_NOTICED_MS,
                    "a tunnel whose key was revoked must not keep showing CONNECTED");

            // ...and comes back by itself: a fresh device, fresh links.
            waitForState(s -> s == ConnectionState.CONNECTED, RECOVERY_TIMEOUT_MS,
                    "the app should recover on its own after re-registering this device");
            assertFetchesThroughTunnel();
        } finally {
            targetContext.startService(
                    new Intent(targetContext, XrayVpnService.class).setAction(XrayVpnService.ACTION_DISCONNECT));
        }

        // --- 7. Disconnect leaves the device online directly ----------------
        waitForState(s -> s == ConnectionState.DISCONNECTED, DEFAULT_TIMEOUT_MS, "disconnect should complete");
        String direct = fetchWithRetries("https://example.com/", 3);
        assertTrue("after disconnecting, the device must be online directly", direct.contains("Example Domain"));
    }

    private static void assertFetchesThroughTunnel() throws InterruptedException {
        // Same bar as e2e/tests/tunnel.spec.ts: the open internet, not a LAN
        // address, with a few retries for REALITY's occasional handshake flake.
        String body = fetchWithRetries("https://example.com/", 4);
        assertTrue("expected real response body from example.com through the tunnel, got: " + body,
                body.contains("Example Domain"));
    }

    /**
     * A plain GET from this test's own thread. Instrumented tests run in the
     * app's process and the TUN excludes nothing, so while connected this
     * can only reach the internet through the tunnel.
     */
    private static String fetchWithRetries(String url, int attempts) throws InterruptedException {
        IOException lastError = null;
        for (int i = 1; i <= attempts; i++) {
            OkHttpClient http = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = http.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (body.contains("Example Domain")) {
                    return body;
                }
                lastError = new IOException("attempt " + i + ": status=" + response.code() + " body=" + body);
            } catch (IOException e) {
                lastError = e;
            }
            Thread.sleep(2000);
        }
        fail("All " + attempts + " attempts to fetch " + url + " failed; last error: " + lastError);
        return null; // unreachable
    }

    private static void waitForState(java.util.function.Predicate<ConnectionState> condition, long timeoutMs,
                                     String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test(VpnStatusBus.state.getValue())) return;
            Thread.sleep(500);
        }
        fail(message + " (state after " + timeoutMs + "ms: " + VpnStatusBus.state.getValue() + ")");
    }

    private static void waitFor(Matcher<View> matcher, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Throwable lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(matcher).check(matches(isDisplayed()));
                return;
            } catch (NoMatchingViewException | NoMatchingRootException | AssertionError e) {
                lastError = e;
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Timed out after " + timeoutMs + "ms waiting for view: " + matcher, lastError);
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
