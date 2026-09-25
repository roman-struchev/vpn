package com.vpn.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.vpn.android.api.TokenStore;
import com.vpn.android.p2p.NatCheck;
import com.vpn.android.p2p.P2pRelaySettingsActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The relay settings screen on a network nobody can reach (a mobile carrier's
 * symmetric NAT, simulated by two fake STUN servers reporting different public
 * ports): turning relaying on must be refused with the explanation, and the
 * relay must stay off.
 */
@RunWith(AndroidJUnit4.class)
public class P2pUnsupportedNetworkTest {

    private final List<DatagramSocket> servers = new ArrayList<>();
    private TokenStore tokenStore;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        tokenStore = new TokenStore(context);
        tokenStore.saveP2pTermsAccepted(true); // skip the one-time consent dialog
        tokenStore.saveP2pRelayState(TokenStore.P2P_RELAY_OFF, 0L);
        tokenStore.saveP2pRelayUnsupportedNetwork(null);
        NatCheck.overrideServersForTests(List.of(fakeStun(40000), fakeStun(40001)));
    }

    @After
    public void tearDown() {
        NatCheck.overrideServersForTests(null);
        tokenStore.saveP2pRelayUnsupportedNetwork(null);
        for (DatagramSocket s : servers) s.close();
    }

    @Test
    public void turningRelayOnIsRefusedWithTheReason() {
        try (ActivityScenario<P2pRelaySettingsActivity> ignored = ActivityScenario.launch(P2pRelaySettingsActivity.class)) {
            onView(withId(R.id.p2pMode1h)).perform(scrollTo(), click());
            onView(withId(R.id.p2pApplyButton)).perform(scrollTo(), click());

            // The check runs off the main thread, which Espresso does not wait for.
            waitForText(R.string.p2p_relay_unsupported_title, 8000);
            onView(withText(containsString("carrier"))).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(matches(isDisplayed()));
            onView(withText(android.R.string.ok)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());

            // Still on the screen, saying why — and nothing was turned on.
            onView(withId(R.id.p2pUnsupportedText)).check(matches(withText(containsString("Wi"))));
            assertEquals(TokenStore.P2P_RELAY_OFF, tokenStore.getP2pRelayMode());
            assertEquals("SYMMETRIC", tokenStore.getP2pRelayUnsupportedNetwork());
        }
    }

    private static void waitForText(int resId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                onView(withText(resId)).check(matches(isDisplayed()));
                return;
            } catch (Throwable notYet) {
                if (System.currentTimeMillis() > deadline) throw notYet;
                android.os.SystemClock.sleep(200);
            }
        }
    }

    private InetSocketAddress fakeStun(int reportPort) throws Exception {
        // 127.0.0.1 explicitly: on Android getLoopbackAddress() can be ::1, and the
        // check sends to 127.0.0.1.
        DatagramSocket s = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        servers.add(s);
        Thread t = new Thread(() -> {
            byte[] buf = new byte[512];
            while (!s.isClosed()) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    s.receive(in);
                    byte[] res = new byte[32];
                    res[0] = 0x01;
                    res[1] = 0x01;
                    res[3] = 12;
                    System.arraycopy(in.getData(), 4, res, 4, 16);
                    res[21] = 0x20;
                    res[23] = 8;
                    res[25] = 0x01;
                    int xport = reportPort ^ 0x2112;
                    res[26] = (byte) (xport >>> 8);
                    res[27] = (byte) xport;
                    byte[] ip = {(byte) 203, 0, 113, 7};
                    byte[] cookie = {0x21, 0x12, (byte) 0xa4, 0x42};
                    for (int i = 0; i < 4; i++) res[28 + i] = (byte) (ip[i] ^ cookie[i]);
                    s.send(new DatagramPacket(res, res.length, in.getSocketAddress()));
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return InetSocketAddress.createUnresolved("127.0.0.1", s.getLocalPort());
    }
}
