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
import static org.hamcrest.CoreMatchers.instanceOf;
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
import com.vpn.android.vpn.XrayVpnService;

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
    private static final long TUNNEL_TIMEOUT_MS = 30_000;

    @Rule
    public GrantPermissionRule notificationPermissionRule =
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);

    @Rule
    public ActivityScenarioRule<LoginActivity> activityRule =
            new ActivityScenarioRule<>(LoginActivity.class);

    private Context targetContext;

    @Before
    public void resetLoginState() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new TokenStore(targetContext).clear();
    }

    @Test
    public void connectsThroughARealNodeAndMovesRealTraffic() throws Exception {
        // --- 1. Register through the real Login screen ----------------------
        String email = "e2e-android-tunnel-" + System.currentTimeMillis() + "@example.com";
        String password = "Test-Passw0rd!";

        onView(withId(R.id.toggleModeButton)).perform(click());
        onView(withId(R.id.emailInput)).perform(typeText(email), closeSoftKeyboard());
        onView(withId(R.id.passwordInput)).perform(typeText(password), closeSoftKeyboard());
        onView(withId(R.id.submitButton)).perform(click());

        waitFor(withId(R.id.bottomNav), DEFAULT_TIMEOUT_MS);
        waitFor(allOf(withId(R.id.trafficText), withText(R.string.state_no_subscription)), DEFAULT_TIMEOUT_MS);

        // --- 2. Activate the free trial (no billing UI exists — see UiFlowTest) ---
        String token = new TokenStore(targetContext).getToken();
        assertNotNull("real UI registration should have persisted a JWT", token);
        activateFreeTrial(BuildConfig.API_BASE_URL, token);

        // Trial subscriptions ARE included in the authenticated
        // /api/v1/user/subscription/links export (SubscriptionExportService
        // #exportVlessLinksForOwnApp) — unlike the public/token-based export,
        // this is a logged-in user fetching their own credentials, exactly
        // what a trial is for. So the trial account this test just created
        // is enough to reach the real node; no paid purchase needed here.

        // --- 3. Add a device through the real "Add device" dialog -----------
        // Required *before* the first connect attempt, not just a nice-to-
        // have: SubscriptionExportService#exportVlessLinksForOwnApp returns
        // an EMPTY link list (autoCreatePrimaryDevice=false) when the user
        // has no devices yet, which XrayVpnService#loadProfileAndConnect
        // then turns into "No subscription links available for this
        // account" -> FATAL_ERROR. XrayVpnService's own
        // registerOrTouchDevice() only runs *after* a tunnel has already
        // come up, so it cannot bootstrap this account's very first device —
        // confirmed live: an earlier version of this test that skipped this
        // step failed with "java.lang.IllegalStateException: No
        // subscription links available for this account" before ever
        // dialing the real node.
        String deviceName = "e2e-android-tunnel-device-" + System.currentTimeMillis();
        onView(withId(R.id.nav_devices)).perform(click());
        waitFor(withId(R.id.addDeviceButton), DEFAULT_TIMEOUT_MS);
        onView(withId(R.id.addDeviceButton)).perform(click());
        onView(instanceOf(android.widget.EditText.class)).perform(typeText(deviceName), closeSoftKeyboard());
        onView(withId(android.R.id.button1)).perform(click());
        waitFor(withText(deviceName), DEFAULT_TIMEOUT_MS);

        // --- 4. Tap the real Connect button and handle the real OS consent dialog ---
        onView(withId(R.id.nav_connect)).perform(click());
        waitFor(allOf(withId(R.id.statusText), withText(R.string.state_disconnected)), DEFAULT_TIMEOUT_MS);

        onView(withId(R.id.connectButton)).perform(click());

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 allowButton = device.wait(Until.findObject(By.res("android", "button1")), 5_000);
        if (allowButton != null) {
            allowButton.click();
        }

        try {
            // Unlike UiFlowTest, this asserts the specific CONNECTED state —
            // a real node is actually up and reachable at this point, so
            // landing anywhere else (ERROR/OPERATOR_BLOCKED) is a real
            // failure worth surfacing, not something to paper over.
            waitFor(allOf(withId(R.id.statusText), withText(R.string.state_connected)), TUNNEL_TIMEOUT_MS);

            // --- 5. The actual assertion: real traffic through the real tunnel ---
            // Same reasoning as e2e/tests/tunnel.spec.ts: not a local/loopback
            // target (the node's own config blocks geoip:private on its
            // outbound side, and would only prove the tunnel can reach the
            // node's own LAN, not the open internet), and REALITY's live
            // handshake against vpn.reality.dest can occasionally flake even
            // with fully correct config — so retry a few full requests
            // rather than asserting on a single attempt.
            String body = fetchThroughTunnelWithRetries("http://example.com/", 4);
            assertTrue("expected real response body from example.com through the tunnel, got: " + body,
                    body.contains("Example Domain"));
        } finally {
            targetContext.startService(
                    new Intent(targetContext, XrayVpnService.class).setAction(XrayVpnService.ACTION_DISCONNECT));
        }
    }

    /**
     * Issues a real GET straight through the device's default route (no
     * proxy configured — the OkHttpClient below is as plain as the app's
     * own networking) so it can only reach example.com by actually
     * transiting the TUN interface XrayVpnService just established. A
     * fresh OkHttpClient per attempt avoids reusing a pooled connection
     * from a failed prior attempt.
     */
    private static String fetchThroughTunnelWithRetries(String url, int attempts) throws InterruptedException {
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
        fail("All " + attempts + " attempts to fetch " + url + " through the tunnel failed; last error: " + lastError);
        return null; // unreachable
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
