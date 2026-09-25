package com.vpn.server.controller;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionUserInfoTest {

    private static Subscription sub(String tariffId, Instant end) {
        Tariff t = new Tariff();
        t.setId(tariffId);
        Subscription s = new Subscription();
        s.setTariff(t);
        s.setTrafficUsedBytes(123L);
        s.setTrafficLimitBytes(1073741824L);
        s.setCurrentPeriodEnd(end);
        return s;
    }

    @Test
    void reportsTheRealTrafficAndEndDate() {
        Instant end = Instant.parse("2026-10-23T10:00:00Z");
        assertEquals("upload=0; download=123; total=1073741824; expire=" + end.getEpochSecond(),
                SubscriptionController.userInfo(sub("pro", end)));
    }

    @Test
    void theTrialHasNoEndDate() {
        assertEquals("upload=0; download=123; total=1073741824; expire=0",
                SubscriptionController.userInfo(sub("trial", Instant.now().plus(36_500, ChronoUnit.DAYS))));
    }

    @Test
    void aTemporaryTariffOnATrialStillHasNoEndDate() {
        Subscription s = sub("trial", Instant.now().plus(36_500, ChronoUnit.DAYS));
        Tariff pro = new Tariff();
        pro.setId("pro");
        s.setOverrideTariff(pro);
        s.setOverrideExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        assertEquals("upload=0; download=123; total=1073741824; expire=0", SubscriptionController.userInfo(s));
    }
}
