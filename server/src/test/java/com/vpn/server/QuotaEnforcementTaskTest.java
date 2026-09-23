package com.vpn.server;

import com.vpn.server.entity.CryptoInvoice;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.User;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.CryptoInvoiceRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.task.QuotaEnforcementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuotaEnforcementTaskTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private CryptoInvoiceRepository cryptoInvoiceRepository;

    @Mock
    private AgentStreamServiceImpl agentStreamService;

    @Mock
    private com.vpn.server.service.BillingService billingService;

    @Mock
    private com.vpn.server.service.RenewalNotifier renewalNotifier;

    @Mock
    private com.vpn.server.service.TrafficNotifier trafficNotifier;

    private QuotaEnforcementTask quotaEnforcementTask;

    @BeforeEach
    void setUp() {
        quotaEnforcementTask = new QuotaEnforcementTask(
                subscriptionRepository,
                cryptoInvoiceRepository,
                agentStreamService,
                billingService,
                renewalNotifier,
                trafficNotifier
        );
    }

    @Test
    void testRunEnforcementWithExpiredSubscription() {
        User user = new User();
        user.setId(10L);

        Subscription expired = new Subscription();
        expired.setId(1L);
        expired.setUser(user);
        expired.setStatus("ACTIVE");
        expired.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));

        when(subscriptionRepository.findExpiredSubscriptions(any(Instant.class)))
                .thenReturn(List.of(expired));
        when(subscriptionRepository.findQuotaExceededSubscriptions())
                .thenReturn(Collections.emptyList());
        when(cryptoInvoiceRepository.findByStatusAndExpiresAtBefore(eq("PENDING"), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        quotaEnforcementTask.runEnforcement();

        assertEquals("EXPIRED", expired.getStatus());
        verify(subscriptionRepository).save(expired);
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testRunEnforcementWithExceededQuota() {
        User user = new User();
        user.setId(20L);

        Subscription exceeded = new Subscription();
        exceeded.setId(2L);
        exceeded.setUser(user);
        exceeded.setStatus("ACTIVE");
        exceeded.setTrafficLimitBytes(20_000_000_000L);
        exceeded.setTrafficUsedBytes(21_000_000_000L);

        when(subscriptionRepository.findExpiredSubscriptions(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(subscriptionRepository.findQuotaExceededSubscriptions())
                .thenReturn(List.of(exceeded));
        when(cryptoInvoiceRepository.findByStatusAndExpiresAtBefore(eq("PENDING"), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        quotaEnforcementTask.runEnforcement();

        assertEquals("EXHAUSTED", exceeded.getStatus());
        verify(subscriptionRepository).save(exceeded);
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testRunEnforcementWithExpiredInvoice() {
        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setId(100L);
        invoice.setStatus("PENDING");
        invoice.setExpiresAt(Instant.now().minus(5, ChronoUnit.MINUTES));

        when(subscriptionRepository.findExpiredSubscriptions(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(subscriptionRepository.findQuotaExceededSubscriptions())
                .thenReturn(Collections.emptyList());
        when(cryptoInvoiceRepository.findByStatusAndExpiresAtBefore(eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(invoice));

        quotaEnforcementTask.runEnforcement();

        assertEquals("EXPIRED", invoice.getStatus());
        verify(cryptoInvoiceRepository).save(invoice);
        // No subscription changed, so no push to nodes
        verify(agentStreamService, never()).pushConfigSyncToAll();
    }

    @Test
    void testRunEnforcementWithAutoRenewalSuccess() {
        User user = new User();
        user.setId(30L);

        com.vpn.server.entity.Tariff tariff = new com.vpn.server.entity.Tariff();
        tariff.setId("standard");

        Subscription expired = new Subscription();
        expired.setId(3L);
        expired.setUser(user);
        expired.setTariff(tariff);
        expired.setStatus("ACTIVE");
        expired.setAutoRenew(true);
        expired.setIsAnnual(false);
        expired.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));

        when(subscriptionRepository.findExpiredSubscriptions(any(Instant.class)))
                .thenReturn(List.of(expired));
        when(subscriptionRepository.findQuotaExceededSubscriptions())
                .thenReturn(Collections.emptyList());
        when(cryptoInvoiceRepository.findByStatusAndExpiresAtBefore(eq("PENDING"), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        quotaEnforcementTask.runEnforcement();

        verify(billingService).purchaseOrRenewSubscription(30L, "standard", false);
        assertEquals("EXPIRED", expired.getStatus());
        verify(subscriptionRepository).save(expired);
        // Since it was renewed, stateChanged is false (user is still subscribed)
        verify(agentStreamService, never()).pushConfigSyncToAll();
    }

    @Test
    void testAutoRenewalBuysTheScheduledCheaperPlan() {
        User user = new User();
        user.setId(31L);

        com.vpn.server.entity.Tariff max = new com.vpn.server.entity.Tariff();
        max.setId("max");
        com.vpn.server.entity.Tariff mid = new com.vpn.server.entity.Tariff();
        mid.setId("mid");

        Subscription expired = new Subscription();
        expired.setId(4L);
        expired.setUser(user);
        expired.setTariff(max);
        expired.setNextTariff(mid);
        expired.setStatus("ACTIVE");
        expired.setAutoRenew(true);
        expired.setIsAnnual(false);
        expired.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));

        when(subscriptionRepository.findExpiredSubscriptions(any(Instant.class)))
                .thenReturn(List.of(expired));
        when(subscriptionRepository.findQuotaExceededSubscriptions())
                .thenReturn(Collections.emptyList());
        when(cryptoInvoiceRepository.findByStatusAndExpiresAtBefore(eq("PENDING"), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        quotaEnforcementTask.runEnforcement();

        verify(billingService).purchaseOrRenewSubscription(31L, "mid", false);
    }

    @Test
    void testFailedAutoRenewalForLackOfBalanceTellsTheUser() {
        User user = new User();
        user.setId(32L);
        com.vpn.server.entity.Tariff max = new com.vpn.server.entity.Tariff();
        max.setId("max");

        Subscription expired = new Subscription();
        expired.setId(5L);
        expired.setUser(user);
        expired.setTariff(max);
        expired.setStatus("ACTIVE");
        expired.setAutoRenew(true);
        expired.setIsAnnual(false);
        expired.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));

        com.vpn.server.service.InsufficientBalanceException shortfall =
                new com.vpn.server.service.InsufficientBalanceException(5_000_000L, 1_000_000L);
        when(billingService.purchaseOrRenewSubscription(32L, "max", false)).thenThrow(shortfall);
        when(subscriptionRepository.findExpiredSubscriptions(any(Instant.class))).thenReturn(List.of(expired));
        when(subscriptionRepository.findQuotaExceededSubscriptions()).thenReturn(Collections.emptyList());
        when(cryptoInvoiceRepository.findByStatusAndExpiresAtBefore(eq("PENDING"), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        quotaEnforcementTask.runEnforcement();

        assertEquals("EXPIRED", expired.getStatus());
        verify(renewalNotifier).notifyRenewalFailed(expired, shortfall);
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testAnnualPlanTrafficResetsMonthlyAndRevivesAnExhaustedPlan() {
        User user = new User();
        user.setId(33L);
        Instant now = Instant.now();
        Subscription annual = new Subscription();
        annual.setId(6L);
        annual.setUser(user);
        annual.setStatus("EXHAUSTED");
        annual.setIsAnnual(true);
        annual.setTrafficUsedBytes(100L);
        annual.setTrafficLimitBytes(100L);
        annual.setTrafficWarningSentAt(now.minus(3, ChronoUnit.DAYS));
        annual.setCurrentPeriodEnd(now.plus(200, ChronoUnit.DAYS));
        annual.setTrafficResetAt(now.minus(1, ChronoUnit.HOURS));

        when(subscriptionRepository.findTrafficResetsDue(any(Instant.class))).thenReturn(List.of(annual));
        when(subscriptionRepository.findExpiredSubscriptions(any(Instant.class))).thenReturn(Collections.emptyList());
        when(subscriptionRepository.findQuotaExceededSubscriptions()).thenReturn(Collections.emptyList());
        when(cryptoInvoiceRepository.findByStatusAndExpiresAtBefore(eq("PENDING"), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        quotaEnforcementTask.runEnforcement();

        assertEquals("ACTIVE", annual.getStatus());
        assertEquals(0L, annual.getTrafficUsedBytes());
        assertNull(annual.getTrafficWarningSentAt());
        assertTrue(annual.getTrafficResetAt().isAfter(now.plus(29, ChronoUnit.DAYS)));
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testRunningOutOfTrafficTellsTheUser() {
        User user = new User();
        user.setId(34L);
        Subscription sub = new Subscription();
        sub.setId(7L);
        sub.setUser(user);
        sub.setStatus("ACTIVE");
        sub.setTrafficUsedBytes(100L);
        sub.setTrafficLimitBytes(100L);

        when(subscriptionRepository.findExpiredSubscriptions(any(Instant.class))).thenReturn(Collections.emptyList());
        when(subscriptionRepository.findQuotaExceededSubscriptions()).thenReturn(List.of(sub));
        when(cryptoInvoiceRepository.findByStatusAndExpiresAtBefore(eq("PENDING"), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        quotaEnforcementTask.runEnforcement();

        assertEquals("EXHAUSTED", sub.getStatus());
        verify(trafficNotifier).notifyExhausted(sub);
        verify(trafficNotifier).warnLowTraffic(any(Instant.class));
    }
}
