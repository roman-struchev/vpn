package com.vpn.android.p2p;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.vpn.android.api.TokenStore;

import org.junit.Test;

/**
 * Consent used to be a checkbox on the settings screen: it had to be re-ticked
 * on every visit, appeared to blink whenever the status call overwrote what the
 * user had just ticked, and was demanded even to switch relaying *off*. It is
 * now a one-time dialog — this pins the rule that decides when it is shown.
 */
public class P2pConsentTest {

    @Test
    public void asksBeforeRelayingStartsIfTheUserNeverAccepted() {
        assertTrue(P2pRelaySettingsActivity.needsConsent(TokenStore.P2P_RELAY_TIMED, false));
        assertTrue(P2pRelaySettingsActivity.needsConsent(TokenStore.P2P_RELAY_ALWAYS, false));
    }

    @Test
    public void asksOnlyOnce() {
        assertFalse(P2pRelaySettingsActivity.needsConsent(TokenStore.P2P_RELAY_TIMED, true));
        assertFalse(P2pRelaySettingsActivity.needsConsent(TokenStore.P2P_RELAY_ALWAYS, true));
    }

    @Test
    public void neverAsksToStopRelaying() {
        // The reported bug: withdrawing from the feature required agreeing to
        // it, which is backwards — and left a user who had not accepted (say,
        // relaying started on another device) unable to turn it off at all.
        assertFalse(P2pRelaySettingsActivity.needsConsent(TokenStore.P2P_RELAY_OFF, false));
        assertFalse(P2pRelaySettingsActivity.needsConsent(TokenStore.P2P_RELAY_OFF, true));
    }

    private static final long HOUR = 3_600_000L;

    @Test
    public void reSelectsTheTimedOptionTheUserActuallyPicked() {
        // The bug this fixes: an 8h window in its last hour has less than an
        // hour left, so the screen re-selected "1 hour" — the persisted
        // duration is what actually answers which button was pressed.
        assertTrue(P2pRelaySettingsActivity.isEightHourWindow(8 * HOUR, 20 * 60_000L));
        assertFalse(P2pRelaySettingsActivity.isEightHourWindow(HOUR, 55 * 60_000L));
    }

    @Test
    public void fallsBackToRemainingTimeForAWindowStartedBeforeDurationsWerePersisted() {
        assertTrue(P2pRelaySettingsActivity.isEightHourWindow(0L, 3 * HOUR));
        assertFalse(P2pRelaySettingsActivity.isEightHourWindow(0L, 40 * 60_000L));
    }
}
