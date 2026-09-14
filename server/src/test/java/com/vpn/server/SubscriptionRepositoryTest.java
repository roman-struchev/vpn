package com.vpn.server;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-query regression test for findExpiredSubscriptions — a Mockito test
 * stubbing this method (see QuotaEnforcementTaskTest) can't catch a bug in
 * the JPQL itself. This previously returned a subscription whose real
 * currentPeriodEnd had passed even while an admin-granted temporary tariff
 * override was still active, which flipped it to EXPIRED and cut the user
 * off mid-override (see Subscription#isExpired's doc for the full story).
 */
@SpringBootTest
@ActiveProfiles("test")
class SubscriptionRepositoryTest {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TariffRepository tariffRepository;

    // Flyway is disabled for the "test" profile (application-test.yml uses
    // Hibernate's ddl-auto: create-drop with no seed data), so the real
    // migration's trial/basic/pro tariff rows don't exist here — create
    // whatever tariffs a test needs itself instead of assuming they're seeded.
    private Tariff tariff(String id) {
        return tariffRepository.findById(id).orElseGet(() -> {
            Tariff t = new Tariff();
            t.setId(id);
            t.setName(id.toUpperCase());
            t.setMonthlyPriceUsdtMicro(0L);
            t.setAnnualPriceUsdtMicro(0L);
            t.setTrafficQuotaBytes(1_000_000_000L);
            t.setMaxDevices(1);
            t.setServerPool("paid");
            return tariffRepository.save(t);
        });
    }

    private Subscription newActiveSubscription(Instant currentPeriodEnd) {
        String unique = UUID.randomUUID().toString();
        User user = new User();
        user.setEmail("sub-repo-test-" + unique + "@example.com");
        user.setReferralCode("t" + unique.substring(0, 8));
        user = userRepository.save(user);

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(tariff("trial"));
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodStart(Instant.now().minus(3, ChronoUnit.DAYS));
        sub.setCurrentPeriodEnd(currentPeriodEnd);
        sub.setTrafficLimitBytes(1_000_000_000L);
        return subscriptionRepository.save(sub);
    }

    @Test
    void findExpiredSubscriptions_includesRealExpiryWithNoOverride() {
        Subscription sub = newActiveSubscription(Instant.now().minus(1, ChronoUnit.HOURS));

        List<Subscription> expired = subscriptionRepository.findExpiredSubscriptions(Instant.now());

        assertTrue(expired.stream().anyMatch(s -> s.getId().equals(sub.getId())));
    }

    @Test
    void findExpiredSubscriptions_excludesSubscriptionWithActiveOverride() {
        Subscription sub = newActiveSubscription(Instant.now().minus(1, ChronoUnit.HOURS));
        sub.setOverrideTariff(tariff("pro"));
        sub.setOverrideExpiresAt(Instant.now().plus(29, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);

        List<Subscription> expired = subscriptionRepository.findExpiredSubscriptions(Instant.now());

        assertFalse(expired.stream().anyMatch(s -> s.getId().equals(sub.getId())));
    }

    @Test
    void findExpiredSubscriptions_includesSubscriptionWhoseOverrideAlsoExpired() {
        Subscription sub = newActiveSubscription(Instant.now().minus(1, ChronoUnit.HOURS));
        sub.setOverrideTariff(tariff("pro"));
        sub.setOverrideExpiresAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        subscriptionRepository.save(sub);

        List<Subscription> expired = subscriptionRepository.findExpiredSubscriptions(Instant.now());

        assertEquals(1, expired.stream().filter(s -> s.getId().equals(sub.getId())).count());
    }
}
