package com.vpn.android.p2p;

import com.vpn.android.api.TokenStore;

/** Pure relay-window rules shared by P2pRelayService's expiry check (kept Android-free so it's unit-testable on the JVM). */
public final class RelayWindow {

    private RelayWindow() {
    }

    /** True once a TIMED window has ended (or never had a valid end) — the relay should then turn itself off. */
    public static boolean isTimedWindowOver(String relayMode, long relayExpiresAtEpochMs, long nowEpochMs) {
        return TokenStore.P2P_RELAY_TIMED.equals(relayMode)
                && (relayExpiresAtEpochMs <= 0 || nowEpochMs >= relayExpiresAtEpochMs);
    }
}
