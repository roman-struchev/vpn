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
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.NoMatchingRootException;
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
 * The genuinely UI-driving counterpart to {@link ApiFlowTest}: instead of
 * calling {@code ApiClient}/{@code TokenStore} directly, this launches the
 * real {@link LoginActivity}, types into the actual on-screen
 * TextInputEditText fields, taps the actual MaterialButtons, and asserts
 * against what the real, rendered view hierarchy shows — the same way a
 * person watching the emulator would. Espresso's {@code onView(...)}
 * calls below run against whatever Activity/Fragment is currently on
 * screen; nothing here talks to ApiClient except the one documented
 * exception (free-trial activation — see below).
 *
 * Flow driven entirely by taps/typing on real views:
 *   Login screen -> toggle to "register" -> type email/password -> submit
 *   -> MainActivity/ConnectFragment appears -> Profile tab shows the
 *   registered email -> Devices tab -> "Add device" dialog -> type a
 *   device name -> device appears in the real RecyclerView list.
 *
 * One step has no UI to drive: activating the free trial. Per
 * ApiFlowTest's own class doc, "the Android app has no billing UI" (no
 * ApiClient.purchase()/activate() call exists, and no screen in this app
 * exposes tariffs/purchase). So — exactly like ApiFlowTest — this test
 * activates the trial with one raw authenticated HTTP POST using the
 * token the real UI registration just produced, then goes back through
 * the UI (Profile tab, then Connect tab) to observe the *result* of that
 * activation reflected in the real, re-fetched-and-rendered screen. That
 * one POST is the only non-UI interaction in this whole test.
 *
 * Stretch goal: also taps the real "Connect" button and, since
 * {@code VpnService.prepare()} pops a one-time OS-owned consent dialog
 * that lives outside this app's own window (Espresso's onView() cannot
 * see or click it), uses UiAutomator's {@link UiDevice} to find and tap
 * that dialog's "Allow"/"OK" button by its stable android:id/button1
 * resource id. After that, {@link XrayVpnService} genuinely runs its
 * connect flow against the real local server. This test does NOT assert
 * a successful tunnel (CONNECTED): no real xray-core node is registered
 * in this run (that needs a whole separate local-agent bootstrap — see
 * ApiFlowTest's class doc for exactly why that's a bigger, separate
 * effort), so the honest, expected outcome is the state machine leaving
 * "disconnected" and then settling on an error/blocked state once
 * SubscriptionExportService reports no ONLINE nodes. Asserting that
 * specific end state would make this test depend on whatever real nodes
 * some *other* concurrently-running suite (e.g. e2e/tests/nodes.spec.ts
 * or tunnel.spec.ts) happens to have registered against the same shared
 * dev DB at the moment this runs — so this test only asserts the status
 * text actually changes away from "disconnected" (proof the tap + the
 * real system dialog actually drove the real VpnService/state machine),
 * then best-effort disconnects to leave the shared emulator clean.
 *
 * Run (same server/emulator setup as ApiFlowTest):
 *   JAVA_HOME=$(/usr/libexec/java_home -v 21) \
 *     ./gradlew :app:connectedDebugAndroidTest \
 *     -PapiBaseUrl=http://10.0.2.2:8080/ \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.vpn.android.UiFlowTest
 */
@RunWith(AndroidJUnit4.class)
public class UiFlowTest {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long DEFAULT_TIMEOUT_MS = 20_000;

    // POST_NOTIFICATIONS is requested unconditionally by MainActivity#onCreate
    // on API 33+; pre-granting it here means that request is satisfied
    // silently instead of popping its own OS permission dialog mid-test.
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
        // LoginActivity redirects straight to MainActivity in onCreate() if
        // tokenStore.isLoggedIn() — a leftover token from a previous test run
        // (this shares the same on-disk EncryptedSharedPreferences file as a
        // real app install) would skip right past the screen this test is
        // meant to exercise. Force a logged-out start every run.
        new TokenStore(targetContext).clear();
    }

    @Test
    public void registerThroughRealUiActivateTrialAndAddDeviceThroughRealUi() throws Exception {
        // --- 1. Register via the real Login screen -------------------------
        // ActivityScenarioRule already launched LoginActivity; since we just
        // cleared the token store it will actually show the login form
        // instead of auto-redirecting.
        String email = "e2e-android-ui-" + System.currentTimeMillis() + "@example.com";
        String password = "Test-Passw0rd!";

        onView(withId(R.id.toggleModeButton)).perform(click()); // switch Login -> Register mode
        onView(withId(R.id.emailInput)).perform(typeText(email), closeSoftKeyboard());
        onView(withId(R.id.passwordInput)).perform(typeText(password), closeSoftKeyboard());
        onView(withId(R.id.submitButton)).perform(click());

        // Real network round-trip happens on a background thread (see
        // com.vpn.android.util.Async) that Espresso has no idling hook into,
        // so we poll for the real result (MainActivity's bottom nav) instead
        // of asserting immediately.
        waitFor(withId(R.id.bottomNav), DEFAULT_TIMEOUT_MS);
        // Confirms the freshly-registered (no subscription yet) profile was
        // actually fetched and rendered on the default Connect tab.
        waitFor(allOf(withId(R.id.trafficText), withText(R.string.state_no_subscription)), DEFAULT_TIMEOUT_MS);

        // --- 2. Activate the free trial (no UI exists for this — see class doc) ---
        String token = new TokenStore(targetContext).getToken();
        assertNotNull("real UI registration should have persisted a JWT", token);
        activateFreeTrial(BuildConfig.API_BASE_URL, token);

        // --- 3. Observe the real Profile screen ------------------------------
        onView(withId(R.id.nav_profile)).perform(click());
        waitFor(allOf(withId(R.id.emailText), withText(email)), DEFAULT_TIMEOUT_MS);

        // --- 4. Back to Connect: the real screen should now reflect the trial ---
        onView(withId(R.id.nav_connect)).perform(click());
        String expiresPrefixFormat = targetContext.getString(R.string.expires_at);
        String expiresPrefix = expiresPrefixFormat.substring(0, expiresPrefixFormat.indexOf('%')).trim();
        waitFor(allOf(withId(R.id.expiresText), withTextStartingWith(expiresPrefix)), DEFAULT_TIMEOUT_MS);

        // --- 5. Add a device through the real "Add device" dialog -----------
        String deviceName = "e2e-android-ui-device-" + System.currentTimeMillis();
        onView(withId(R.id.nav_devices)).perform(click());
        waitFor(withId(R.id.addDeviceButton), DEFAULT_TIMEOUT_MS);
        onView(withId(R.id.addDeviceButton)).perform(click());
        // The dialog's EditText has no id (created programmatically); it's
        // the only EditText in the topmost (dialog) window, so matching by
        // class is unambiguous. Espresso's default root matcher restricts
        // onView() to the focused/topmost window, so this cannot accidentally
        // hit some other EditText behind the dialog.
        onView(instanceOf(android.widget.EditText.class)).perform(typeText(deviceName), closeSoftKeyboard());
        // Not matching by withText(R.string.add_device_action) here: the
        // AlertDialog re-uses that exact same string for both its title
        // (setTitle) and its positive button (setPositiveButton), so a text
        // matcher is ambiguous within the dialog itself. The standard
        // AlertDialog button id is unambiguous.
        onView(withId(android.R.id.button1)).perform(click());

        waitFor(withText(deviceName), DEFAULT_TIMEOUT_MS);

        // --- 6. Stretch goal: tap Connect and handle the real OS consent dialog ---
        driveConnectFlowAndConfirmStateChanged();
    }

    private void driveConnectFlowAndConfirmStateChanged() throws Exception {
        onView(withId(R.id.nav_connect)).perform(click());
        waitFor(allOf(withId(R.id.statusText), withText(R.string.state_disconnected)), DEFAULT_TIMEOUT_MS);

        onView(withId(R.id.connectButton)).perform(click());

        // VpnService.prepare() has never been granted for this app on this
        // AVD yet, so Android now shows its own system "Connection request"
        // dialog. That window belongs to com.android.vpndialogs, not this
        // app, so it is invisible to Espresso's onView(); UiAutomator can
        // see and click across that boundary.
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 allowButton = device.wait(Until.findObject(By.res("android", "button1")), 5_000);
        if (allowButton != null) {
            allowButton.click();
        }
        // If the dialog didn't appear at all (e.g. this app/AVD combination
        // already had consent granted from an earlier manual run), that's
        // fine too — connect() proceeds immediately either way.

        try {
            waitFor(allOf(withId(R.id.statusText), not(withText(R.string.state_disconnected))), 15_000);
        } finally {
            // Best-effort cleanup: leave no foreground VPN service/tunnel
            // running on this shared AVD regardless of which state the real
            // connect attempt above landed on (see class doc for why we
            // don't assert a specific terminal state here).
            targetContext.startService(
                    new Intent(targetContext, XrayVpnService.class).setAction(XrayVpnService.ACTION_DISCONNECT));
        }
    }

    private static Matcher<View> withTextStartingWith(String prefix) {
        return new org.hamcrest.TypeSafeMatcher<View>() {
            @Override
            public boolean matchesSafely(View item) {
                if (!(item instanceof android.widget.TextView)) return false;
                CharSequence text = ((android.widget.TextView) item).getText();
                return text != null && text.toString().startsWith(prefix);
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("a TextView with text starting with \"" + prefix + "\"");
            }
        };
    }

    /**
     * Polls a real-view assertion instead of asserting once immediately.
     * Needed because this app's networking runs on a plain background
     * thread (see com.vpn.android.util.Async) that Espresso's automatic
     * synchronization does not know how to wait on — unlike, say, AsyncTask
     * or coroutines-with-an-IdlingResource. Every check here still goes
     * through the real Espresso view-matching machinery; this only retries
     * it until the real background work finishes or the timeout elapses.
     */
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

    /**
     * Same reasoning as ApiFlowTest#activateFreeTrial: the Android app has
     * no billing/trial UI to tap, so this is the shortest path to a real
     * active subscription this test can produce without an admin session.
     */
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
