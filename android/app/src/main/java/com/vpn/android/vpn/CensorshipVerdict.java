package com.vpn.android.vpn;

/**
 * Decision function behind the "honest blocking screen" from PLAN.md §6:
 * probe a known-reachable Russian host and a foreign test host. If the
 * whitelisted host answers but the foreign one does not, the operator (not
 * our service) is restricting traffic, and the UI should say so plainly
 * instead of showing a generic error.
 *
 * Kept as a pure function of the two probe outcomes so it can be unit
 * tested without performing real network I/O; {@link CensorshipProbeService}
 * does the actual HTTP probing and calls into this.
 */
public final class CensorshipVerdict {

    public enum Result {
        /** Both probes reached their targets: no operator-level restriction detected. */
        OK,
        /** Whitelisted host reachable, foreign host is not: operator is restricting traffic. */
        OPERATOR_RESTRICTION,
        /** Neither host reachable: looks like a generic connectivity problem, not censorship. */
        NO_CONNECTIVITY
    }

    private CensorshipVerdict() {
    }

    public static Result evaluate(boolean whitelistHostReachable, boolean foreignHostReachable) {
        if (foreignHostReachable) {
            // The target we actually care about works, regardless of the whitelist probe.
            return Result.OK;
        }
        if (whitelistHostReachable) {
            // Russian resources load, the foreign/VPN target does not: this is the
            // classic operator-restriction signature, not a generic outage.
            return Result.OPERATOR_RESTRICTION;
        }
        return Result.NO_CONNECTIVITY;
    }
}
