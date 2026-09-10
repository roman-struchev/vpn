package com.vpn.android.vpn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Conformance tests for the Smart Reconnect Backoff invariants from
 * docs/PLAN.md §6 / docs/ROADMAP_PROGRESS.md §1.5.
 */
public class ReconnectBackoffPolicyTest {

    @Test
    public void firstFailurePauseIsNeverShorterThanFifteenSeconds() {
        // Even if a misconfigured/attacker-controlled server sent a too-short value,
        // the client must clamp it — this is the exact mitigation for the ban-window
        // escalation described in PLAN.md §6.
        ReconnectBackoffPolicy policy = new ReconnectBackoffPolicy(1, 3, "firefox");
        ReconnectBackoffPolicy.Decision decision = policy.onFailure();
        assertTrue("first failure delay must be >= 15s, was " + decision.delaySeconds,
                decision.delaySeconds >= ReconnectBackoffPolicy.MIN_INITIAL_BACKOFF_SEC);
    }

    @Test
    public void initialBackoffIsClampedToTheBandFromTransportPolicy() {
        ReconnectBackoffPolicy tooLow = new ReconnectBackoffPolicy(0, 3, "firefox");
        ReconnectBackoffPolicy tooHigh = new ReconnectBackoffPolicy(999, 3, "firefox");

        assertEquals(ReconnectBackoffPolicy.MIN_INITIAL_BACKOFF_SEC, tooLow.onFailure().delaySeconds);
        assertEquals(ReconnectBackoffPolicy.MAX_INITIAL_BACKOFF_SEC, tooHigh.onFailure().delaySeconds);
    }

    @Test
    public void doesNotSwitchNodeBeforeTheConfiguredFailureThreshold() {
        ReconnectBackoffPolicy policy = new ReconnectBackoffPolicy(15, 3, "firefox");

        assertFalse(policy.onFailure().switchNode); // 1st failure
        assertFalse(policy.onFailure().switchNode); // 2nd failure
        assertTrue(policy.onFailure().switchNode);  // 3rd failure -> switch
    }

    @Test
    public void switchThresholdIsClampedToTwoOrThree() {
        ReconnectBackoffPolicy tooEager = new ReconnectBackoffPolicy(15, 1, "firefox");
        assertFalse("must not switch after a single failure", tooEager.onFailure().switchNode);
        assertTrue(tooEager.onFailure().switchNode); // clamped to 2

        ReconnectBackoffPolicy tooPatient = new ReconnectBackoffPolicy(15, 10, "firefox");
        assertFalse(tooPatient.onFailure().switchNode);
        assertFalse(tooPatient.onFailure().switchNode);
        assertTrue(tooPatient.onFailure().switchNode); // clamped to 3
    }

    @Test
    public void successResetsTheFailureStreak() {
        ReconnectBackoffPolicy policy = new ReconnectBackoffPolicy(15, 3, "firefox");
        policy.onFailure();
        policy.onFailure();
        policy.onSuccess();

        assertEquals(0, policy.getConsecutiveFailuresOnNode());
        assertFalse("a fresh streak must not immediately switch nodes", policy.onFailure().switchNode);
    }

    @Test
    public void fingerprintNeverChangesAcrossRetriesOrNodeSwitchesWithinASession() {
        ReconnectBackoffPolicy policy = new ReconnectBackoffPolicy(15, 2, "edge");
        String fingerprint = policy.getFingerprint();

        for (int i = 0; i < 10; i++) {
            policy.onFailure();
            assertEquals("fingerprint must stay fixed for the session, per PLAN.md §6",
                    fingerprint, policy.getFingerprint());
        }
    }

    @Test
    public void rejectsAFingerprintThatIsNotARealBrowser() {
        assertThrows(IllegalArgumentException.class, () -> new ReconnectBackoffPolicy(15, 3, "random"));
        assertThrows(IllegalArgumentException.class, () -> new ReconnectBackoffPolicy(15, 3, null));
    }
}
