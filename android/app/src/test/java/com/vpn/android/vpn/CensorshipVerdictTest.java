package com.vpn.android.vpn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers the "honest blocking screen" decision from docs/PLAN.md §6: a
 * reachable whitelisted host plus an unreachable foreign host must be read
 * as an operator restriction, not a generic app error.
 */
public class CensorshipVerdictTest {

    @Test
    public void bothReachableMeansOk() {
        assertEquals(CensorshipVerdict.Result.OK, CensorshipVerdict.evaluate(true, true));
    }

    @Test
    public void whitelistReachableButForeignBlockedMeansOperatorRestriction() {
        assertEquals(CensorshipVerdict.Result.OPERATOR_RESTRICTION, CensorshipVerdict.evaluate(true, false));
    }

    @Test
    public void neitherReachableMeansGenericConnectivityProblem() {
        assertEquals(CensorshipVerdict.Result.NO_CONNECTIVITY, CensorshipVerdict.evaluate(false, false));
    }

    @Test
    public void foreignReachableAloneIsStillOk() {
        // The target we actually care about works; a flaky/unreachable whitelist probe
        // host must not itself produce a false "no connectivity" reading.
        assertEquals(CensorshipVerdict.Result.OK, CensorshipVerdict.evaluate(false, true));
    }
}
