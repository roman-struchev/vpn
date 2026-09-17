package com.vpn.android.p2p;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.vpn.android.api.TokenStore;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class RelayWindowTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void timedWindowStillRunningIsNotOver() {
        assertFalse(RelayWindow.isTimedWindowOver(TokenStore.P2P_RELAY_TIMED, NOW + TimeUnit.MINUTES.toMillis(1), NOW));
    }

    @Test
    public void timedWindowPastExpiryIsOver() {
        // "На 1 час", checked 9 hours later.
        long expiresAt = NOW - TimeUnit.HOURS.toMillis(8);
        assertTrue(RelayWindow.isTimedWindowOver(TokenStore.P2P_RELAY_TIMED, expiresAt, NOW));
        assertTrue(RelayWindow.isTimedWindowOver(TokenStore.P2P_RELAY_TIMED, NOW, NOW));
    }

    @Test
    public void timedWithoutExpiryIsTreatedAsOver() {
        assertTrue(RelayWindow.isTimedWindowOver(TokenStore.P2P_RELAY_TIMED, 0L, NOW));
    }

    @Test
    public void alwaysAndOffNeverExpire() {
        assertFalse(RelayWindow.isTimedWindowOver(TokenStore.P2P_RELAY_ALWAYS, 0L, NOW));
        assertFalse(RelayWindow.isTimedWindowOver(TokenStore.P2P_RELAY_OFF, 0L, NOW));
    }
}
