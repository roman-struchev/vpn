package com.vpn.android.billing;

import static org.junit.Assert.*;

import com.vpn.android.api.model.TariffInfo;
import com.vpn.android.api.model.UserProfile;

import org.junit.Test;

import java.util.List;

public class PlanSummaryTest {

    private static UserProfile profileOn(String tariffId, long used, long limit, boolean noExpiry) {
        UserProfile profile = new UserProfile();
        profile.hasActiveSubscription = true;
        profile.subscription = new UserProfile.SubscriptionInfo();
        profile.subscription.tariffId = tariffId;
        profile.subscription.trafficUsedBytes = used;
        profile.subscription.trafficLimitBytes = limit;
        profile.subscription.noExpiry = noExpiry;
        profile.subscription.expiresAt = "2026-10-18T21:40:00Z";
        return profile;
    }

    private static TariffInfo tariff(String id, String name, long priceMicro, Integer devices) {
        TariffInfo t = new TariffInfo();
        t.id = id;
        t.name = name;
        t.monthlyPriceUsdtMicro = priceMicro;
        t.maxDevices = devices;
        return t;
    }

    private static final List<TariffInfo> CATALOGUE = List.of(
            tariff("trial", "Пробный", 0, 1),
            tariff("pro", "Pro", 2_000_000, 5));

    @Test
    public void namesThePlanAndItsAllowanceFromTheCatalogue() {
        PlanSummary plan = PlanSummary.of(profileOn("pro", 0, 100L * 1024 * 1024 * 1024, false), CATALOGUE);

        assertTrue(plan.hasSubscription());
        assertEquals("Pro", plan.planName());
        assertEquals(Integer.valueOf(5), plan.maxDevices());
        assertEquals(2.0, plan.monthlyPriceUsdt(), 0.0001);
        assertFalse(plan.isFree());
    }

    @Test
    public void aFreePlanReadsAsFreeRatherThanAsZeroDollars() {
        PlanSummary plan = PlanSummary.of(profileOn("trial", 0, 1024, true), CATALOGUE);

        assertEquals("Пробный", plan.planName());
        assertTrue(plan.isFree());
    }

    @Test
    public void anUnknownOrUnreachableCatalogueStillNamesSomething() {
        // The catalogue call is a second request and can fail on its own, or a
        // server can serve a tariff this build has never heard of. Showing a
        // raw id is poor; showing nothing at all is worse.
        PlanSummary fromId = PlanSummary.of(profileOn("enterprise", 0, 1024, false), CATALOGUE);
        assertEquals("Enterprise", fromId.planName());
        assertNull("no catalogue entry means no device allowance to promise", fromId.maxDevices());

        PlanSummary noCatalogue = PlanSummary.of(profileOn("pro", 0, 1024, false), null);
        assertEquals("Pro", noCatalogue.planName());
    }

    @Test
    public void noSubscriptionIsItsOwnState() {
        UserProfile profile = new UserProfile();
        profile.hasActiveSubscription = false;

        PlanSummary plan = PlanSummary.of(profile, CATALOGUE);

        assertFalse(plan.hasSubscription());
        assertNull(plan.planName());
        assertEquals(PlanSummary.none().trafficPercent(), plan.trafficPercent());
        assertFalse(PlanSummary.of(null, CATALOGUE).hasSubscription());
    }

    @Test
    public void trafficPercentIsClampedAndSurvivesAnUnlimitedQuota() {
        assertEquals(0, PlanSummary.of(profileOn("pro", 5_000, 0, false), CATALOGUE).trafficPercent());
        assertEquals(50, PlanSummary.of(profileOn("pro", 512, 1024, false), CATALOGUE).trafficPercent());
        assertEquals(100, PlanSummary.of(profileOn("pro", 99_999, 1024, false), CATALOGUE).trafficPercent());
    }

    @Test
    public void runningOutIsFlaggedBeforeTheQuotaActuallyEnds() {
        // The point is to warn while the user can still act, not to announce it
        // after the tunnel has already stopped passing traffic.
        assertFalse(PlanSummary.of(profileOn("pro", 800, 1000, false), CATALOGUE).isRunningOut());
        assertTrue(PlanSummary.of(profileOn("pro", 900, 1000, false), CATALOGUE).isRunningOut());
        assertFalse("an unlimited quota can never run out",
                PlanSummary.of(profileOn("pro", 10_000, 0, false), CATALOGUE).isRunningOut());
    }

    @Test
    public void anExpiryIsOnlyReportedWhenThePlanActuallyHasOne() {
        assertNull(PlanSummary.of(profileOn("trial", 0, 1024, true), CATALOGUE).expiresAtIso());
        assertEquals("2026-10-18T21:40:00Z",
                PlanSummary.of(profileOn("pro", 0, 1024, false), CATALOGUE).expiresAtIso());
    }

    @Test
    public void keepsAPlanThatRanOutOfTrafficWithWhyAndWhenItComesBack() {
        UserProfile profile = profileOn("pro", 100, 100, false);
        profile.hasActiveSubscription = false;
        profile.inactiveReason = "TRAFFIC_USED_UP";
        profile.subscription.status = "EXHAUSTED";
        profile.subscription.trafficResetAt = "2026-10-01T00:00:00Z";

        PlanSummary plan = PlanSummary.of(profile, CATALOGUE);

        assertTrue(plan.hasSubscription());
        assertTrue(plan.isExhausted());
        assertFalse(plan.isRunningOut());
        assertEquals("TRAFFIC_USED_UP", plan.inactiveReason());
        assertEquals("2026-10-01T00:00:00Z", plan.refillAtIso());
    }

    @Test
    public void saysWhyThereIsNoPlan() {
        UserProfile profile = new UserProfile();
        profile.inactiveReason = "EXPIRED";
        PlanSummary plan = PlanSummary.of(profile, CATALOGUE);
        assertFalse(plan.hasSubscription());
        assertEquals("EXPIRED", plan.inactiveReason());
    }

    @Test
    public void tellsARenewalTheBalanceWontCover() {
        UserProfile profile = profileOn("pro", 0, 100, false);
        profile.balanceUsdtMicro = 500_000;
        profile.subscription.autoRenew = true;
        profile.subscription.renewalPriceUsdtMicro = 2_000_000;

        PlanSummary.Renewal renewal = PlanSummary.of(profile, CATALOGUE).renewal();

        assertNotNull(renewal);
        assertEquals(1.5, renewal.shortfallUsdt, 0.001);
        assertNull(renewal.nextPlanName);
    }
}
