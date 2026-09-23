package com.vpn.android.billing;

import com.vpn.android.api.model.TariffInfo;
import com.vpn.android.api.model.UserProfile;

import java.util.List;

/**
 * Everything the app needs to tell a user what plan they are on, derived from
 * the two things the server already returns separately: the subscription
 * (GET /user/profile — traffic, expiry, and a bare {@code tariffId}) and the
 * tariff catalogue (GET /user/tariffs — the human name, the price, the device
 * allowance).
 *
 * Until this existed the apps showed neither: the connect screen had a raw
 * "Traffic: 0.00 / 1 GB" bar and the profile tab had a balance, so a user
 * could not see which plan they were on, what it allows, when it renews, or
 * how to change it — reported by the repo owner ("нет информации о тарифе").
 *
 * Plain Java with no Android dependencies: the parts worth pinning down are
 * the fallbacks (an unknown tariff id, a subscription that is missing
 * entirely, a zero traffic limit) and they should not need a device to test.
 */
public final class PlanSummary {

    private final String planName;
    private final String tariffId;
    private final double monthlyPriceUsdt;
    private final Integer maxDevices;
    private final long trafficUsedBytes;
    private final long trafficLimitBytes;
    private final String expiresAtIso;
    private final boolean noExpiry;
    private final boolean hasSubscription;

    // Set by of() after construction — the parts that say what the plan is
    // doing, not just what it is.
    private boolean exhausted;
    private String inactiveReason;
    private String refillAtIso;
    private Renewal renewal;

    /** What auto-renewal will do at the end of a paid period. */
    public static final class Renewal {
        public final double priceUsdt;
        /** The cheaper plan it renews into, if one was scheduled; else null. */
        public final String nextPlanName;
        /** How much the balance is short of the renewal; 0 when covered. */
        public final double shortfallUsdt;

        Renewal(double priceUsdt, String nextPlanName, double shortfallUsdt) {
            this.priceUsdt = priceUsdt;
            this.nextPlanName = nextPlanName;
            this.shortfallUsdt = shortfallUsdt;
        }
    }

    private PlanSummary(String planName, String tariffId, double monthlyPriceUsdt, Integer maxDevices,
                        long trafficUsedBytes, long trafficLimitBytes, String expiresAtIso,
                        boolean noExpiry, boolean hasSubscription) {
        this.planName = planName;
        this.tariffId = tariffId;
        this.monthlyPriceUsdt = monthlyPriceUsdt;
        this.maxDevices = maxDevices;
        this.trafficUsedBytes = trafficUsedBytes;
        this.trafficLimitBytes = trafficLimitBytes;
        this.expiresAtIso = expiresAtIso;
        this.noExpiry = noExpiry;
        this.hasSubscription = hasSubscription;
    }

    /** No active plan — the card shows a "choose a plan" state instead of blanks. */
    public static PlanSummary none() {
        return new PlanSummary(null, null, 0, null, 0, 0, null, false, false);
    }

    /**
     * @param tariffs the catalogue; may be null or stale (an older server, or
     *                the catalogue call failing) — the summary then falls back
     *                to the subscription's own id rather than showing nothing.
     */
    public static PlanSummary of(UserProfile profile, List<TariffInfo> tariffs) {
        if (profile == null) {
            return none();
        }
        // A plan that ran out of traffic still comes back (status EXHAUSTED)
        // and is still the user's plan until it ends; older servers only
        // ever sent a working one.
        UserProfile.SubscriptionInfo sub = profile.subscription;
        boolean exhausted = sub != null && "EXHAUSTED".equals(sub.status);
        String reason = profile.hasActiveSubscription
                ? null
                : profile.inactiveReason != null ? profile.inactiveReason : (exhausted ? null : "NONE");
        if (sub == null || sub.tariffId == null || !(profile.hasActiveSubscription || exhausted)) {
            PlanSummary none = none();
            none.inactiveReason = reason;
            return none;
        }
        TariffInfo tariff = findTariff(tariffs, sub.tariffId);

        String name = tariff != null && tariff.name != null && !tariff.name.isBlank()
                ? tariff.name
                : capitalize(sub.tariffId);

        PlanSummary plan = new PlanSummary(
                name,
                sub.tariffId,
                tariff != null ? tariff.monthlyPriceUsdt() : 0,
                tariff != null ? tariff.maxDevices : null,
                sub.trafficUsedBytes,
                sub.trafficLimitBytes,
                sub.expiresAt,
                sub.noExpiry,
                true);
        plan.exhausted = exhausted;
        plan.inactiveReason = reason;
        String end = plan.expiresAtIso();
        plan.refillAtIso = sub.trafficResetAt != null && end != null && sub.trafficResetAt.compareTo(end) < 0
                ? sub.trafficResetAt
                : end;
        if (!exhausted && end != null && sub.autoRenew && sub.renewalPriceUsdtMicro > 0) {
            double price = sub.renewalPriceUsdtMicro / 1_000_000.0;
            double shortfall = Math.max(0, Math.round((price - profile.balanceUsdt()) * 100) / 100.0);
            TariffInfo next = findTariff(tariffs, sub.nextTariffId);
            String nextName = sub.nextTariffId == null ? null
                    : next != null && next.name != null && !next.name.isBlank() ? next.name : capitalize(sub.nextTariffId);
            plan.renewal = new Renewal(price, nextName, shortfall);
        }
        return plan;
    }

    private static TariffInfo findTariff(List<TariffInfo> tariffs, String tariffId) {
        if (tariffs == null || tariffId == null) {
            return null;
        }
        for (TariffInfo tariff : tariffs) {
            if (tariffId.equalsIgnoreCase(tariff.id)) {
                return tariff;
            }
        }
        return null;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    public boolean hasSubscription() { return hasSubscription; }

    /** Out of traffic: still the user's plan until it ends, but nothing works. */
    public boolean isExhausted() { return exhausted; }

    /** TRIAL_USED_UP, TRAFFIC_USED_UP, EXPIRED, NONE — or null while the plan works. */
    public String inactiveReason() { return inactiveReason; }

    /** When a spent plan gets traffic back by itself: an annual plan's monthly reset, else its end. */
    public String refillAtIso() { return refillAtIso; }

    /** Null unless this is a paid plan that auto-renews. */
    public Renewal renewal() { return renewal; }

    /** Null only when there is no subscription at all. */
    public String planName() { return planName; }

    public String tariffId() { return tariffId; }

    /** A free plan (the trial) reads as free rather than as "$0.00". */
    public boolean isFree() { return monthlyPriceUsdt <= 0; }

    public double monthlyPriceUsdt() { return monthlyPriceUsdt; }

    /** Null when the catalogue could not be resolved — the caller then omits the line. */
    public Integer maxDevices() { return maxDevices; }

    public long trafficUsedBytes() { return trafficUsedBytes; }

    public long trafficLimitBytes() { return trafficLimitBytes; }

    /** Null when the plan has no expiry, or none is known. */
    public String expiresAtIso() { return noExpiry ? null : expiresAtIso; }

    public boolean noExpiry() { return noExpiry; }

    /** 0-100, clamped. An unlimited (or unknown) quota reads as 0 rather than as a divide by zero. */
    public int trafficPercent() {
        if (trafficLimitBytes <= 0) {
            return 0;
        }
        long percent = Math.round(100.0 * trafficUsedBytes / trafficLimitBytes);
        return (int) Math.max(0, Math.min(100, percent));
    }

    /** True once little enough is left that the user should be told before it runs out mid-connection. */
    public boolean isRunningOut() {
        return !exhausted && trafficLimitBytes > 0 && trafficPercent() >= 90;
    }
}
