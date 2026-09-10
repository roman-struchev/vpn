package com.vpn.android.vpn;

/**
 * Smart Reconnect Backoff, per docs/ROADMAP_PROGRESS.md §1.5 and
 * docs/PLAN.md §6: a naive "drop -&gt; immediately try another node/fingerprint"
 * pattern is itself a detectable signature that extends a TSPU ban window
 * from 120s to 600s. So:
 *
 * <ul>
 *   <li>the pause after the first failure is never shorter than the policy's
 *       {@code initialBackoffSec} (clamped to the 15-20s band the server
 *       transport_policy is expected to send);</li>
 *   <li>the node is only rotated after {@code maxRetriesBeforeNodeSwitch}
 *       (2-3) <em>consecutive</em> failures on the current node;</li>
 *   <li>the TLS fingerprint is fixed for the lifetime of this policy instance
 *       (one instance per connection session) and is never re-rolled on
 *       retry or node switch.</li>
 * </ul>
 *
 * Pure Java, no Android dependency, so it is covered directly by JUnit
 * "conformance" tests without Robolectric/instrumentation.
 */
public class ReconnectBackoffPolicy {

    /** Architecture invariant: first-failure pause must fall in [15, 20]s. */
    public static final int MIN_INITIAL_BACKOFF_SEC = 15;
    public static final int MAX_INITIAL_BACKOFF_SEC = 20;
    private static final int MAX_BACKOFF_SEC = 300;

    private final int initialBackoffSec;
    private final int maxRetriesBeforeNodeSwitch;
    private final String fingerprint;

    private int consecutiveFailuresOnNode = 0;

    public ReconnectBackoffPolicy(int initialBackoffSec, int maxRetriesBeforeNodeSwitch, String fingerprint) {
        this.initialBackoffSec = clamp(initialBackoffSec, MIN_INITIAL_BACKOFF_SEC, MAX_INITIAL_BACKOFF_SEC);
        this.maxRetriesBeforeNodeSwitch = Math.max(2, Math.min(3, maxRetriesBeforeNodeSwitch));
        if (fingerprint == null || !(fingerprint.equals("firefox") || fingerprint.equals("edge"))) {
            throw new IllegalArgumentException(
                    "fingerprint must be a real browser fingerprint (firefox|edge), got: " + fingerprint);
        }
        this.fingerprint = fingerprint;
    }

    /** Fixed for the whole session; a reconnect must never re-roll this. */
    public String getFingerprint() {
        return fingerprint;
    }

    public static final class Decision {
        public final int delaySeconds;
        public final boolean switchNode;

        Decision(int delaySeconds, boolean switchNode) {
            this.delaySeconds = delaySeconds;
            this.switchNode = switchNode;
        }
    }

    /** Call once per connection failure. Returns how long to wait and whether to rotate nodes. */
    public Decision onFailure() {
        consecutiveFailuresOnNode++;

        boolean switchNode = consecutiveFailuresOnNode >= maxRetriesBeforeNodeSwitch;
        // delay grows with the failure streak but never dips below the mandated
        // first-failure floor, and is capped to avoid an unbounded wait.
        long delay = (long) initialBackoffSec * consecutiveFailuresOnNode;
        int delaySeconds = (int) Math.min(delay, MAX_BACKOFF_SEC);

        if (switchNode) {
            consecutiveFailuresOnNode = 0;
        }
        return new Decision(delaySeconds, switchNode);
    }

    /** Call on a successful reconnect to reset the failure streak on the (possibly new) node. */
    public void onSuccess() {
        consecutiveFailuresOnNode = 0;
    }

    public int getConsecutiveFailuresOnNode() {
        return consecutiveFailuresOnNode;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
