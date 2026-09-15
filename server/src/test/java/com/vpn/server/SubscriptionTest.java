package com.vpn.server;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Subscription#isExpired/getEffectiveTariff/getEffectiveExpiresAt —
 * specifically the interaction with an admin-granted temporary tariff
 * override, where the whole point is to keep access alive even after the
 * real currentPeriodEnd has passed. A regression here previously let a real
 * subscription expiry silently strand a user mid-override (see
 * QuotaEnforcementTaskTest for the enforcement-task side of the same fix).
 */
class SubscriptionTest {

    private Tariff tariff(String id) {
        Tariff t = new Tariff();
        t.setId(id);
        t.setName(id.toUpperCase());
        return t;
    }

    private Subscription subscriptionWithRealPeriodEnd(Instant end) {
        Subscription sub = new Subscription();
        sub.setTariff(tariff("trial"));
        sub.setCurrentPeriodEnd(end);
        return sub;
    }

    @Test
    void isExpired_trueWhenRealPeriodEndedAndNoOverride() {
        Subscription sub = subscriptionWithRealPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));
        assertTrue(sub.isExpired());
    }

    @Test
    void isExpired_falseWhenRealPeriodStillActive() {
        Subscription sub = subscriptionWithRealPeriodEnd(Instant.now().plus(1, ChronoUnit.HOURS));
        assertFalse(sub.isExpired());
    }

    @Test
    void isExpired_falseWhenRealPeriodEndedButOverrideStillActive() {
        Subscription sub = subscriptionWithRealPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));
        sub.setOverrideTariff(tariff("pro"));
        sub.setOverrideExpiresAt(Instant.now().plus(29, ChronoUnit.DAYS));
        assertFalse(sub.isExpired());
    }

    @Test
    void isExpired_trueWhenRealPeriodEndedAndOverrideAlsoExpired() {
        Subscription sub = subscriptionWithRealPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));
        sub.setOverrideTariff(tariff("pro"));
        sub.setOverrideExpiresAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        assertTrue(sub.isExpired());
    }

    @Test
    void getEffectiveTariff_returnsOverrideWhenActive() {
        Subscription sub = subscriptionWithRealPeriodEnd(Instant.now().plus(1, ChronoUnit.HOURS));
        Tariff pro = tariff("pro");
        sub.setOverrideTariff(pro);
        sub.setOverrideExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        assertEquals(pro, sub.getEffectiveTariff());
    }

    @Test
    void getEffectiveTariff_returnsRealTariffWhenOverrideExpired() {
        Subscription sub = subscriptionWithRealPeriodEnd(Instant.now().plus(1, ChronoUnit.HOURS));
        sub.setOverrideTariff(tariff("pro"));
        sub.setOverrideExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        assertEquals("trial", sub.getEffectiveTariff().getId());
    }

    @Test
    void getEffectiveExpiresAt_returnsOverrideWhenItOutlastsRealPeriod() {
        Instant realEnd = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant overrideEnd = Instant.now().plus(29, ChronoUnit.DAYS);
        Subscription sub = subscriptionWithRealPeriodEnd(realEnd);
        sub.setOverrideTariff(tariff("pro"));
        sub.setOverrideExpiresAt(overrideEnd);
        assertEquals(overrideEnd, sub.getEffectiveExpiresAt());
    }

    @Test
    void getEffectiveExpiresAt_returnsRealPeriodEndWhenNoOverride() {
        Instant realEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        Subscription sub = subscriptionWithRealPeriodEnd(realEnd);
        assertEquals(realEnd, sub.getEffectiveExpiresAt());
    }

    @Test
    void hasNoExpiry_falseForARealNearTermPeriodEnd() {
        Subscription sub = subscriptionWithRealPeriodEnd(Instant.now().plus(3, ChronoUnit.DAYS));
        assertFalse(sub.hasNoExpiry());
    }

    @Test
    void hasNoExpiry_trueForTrialsFarFutureSentinel() {
        // Mirrors BillingService.NO_EXPIRY_DAYS (36,500 days / 100 years) —
        // trial grants no longer carry a real time limit, only a traffic cap.
        Subscription sub = subscriptionWithRealPeriodEnd(Instant.now().plus(36_500, ChronoUnit.DAYS));
        assertTrue(sub.hasNoExpiry());
    }
}
