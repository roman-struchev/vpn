package com.vpn.server.task;

import com.vpn.server.entity.CryptoInvoice;
import com.vpn.server.entity.Subscription;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.CryptoInvoiceRepository;
import com.vpn.server.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class QuotaEnforcementTask {

    private static final Logger log = LoggerFactory.getLogger(QuotaEnforcementTask.class);
    private static final int STALE_INVOICE_RETENTION_DAYS = 15;

    private final SubscriptionRepository subscriptionRepository;
    private final CryptoInvoiceRepository cryptoInvoiceRepository;
    private final AgentStreamServiceImpl agentStreamService;
    private final com.vpn.server.service.BillingService billingService;
    private final com.vpn.server.service.RenewalNotifier renewalNotifier;

    public QuotaEnforcementTask(
            SubscriptionRepository subscriptionRepository,
            CryptoInvoiceRepository cryptoInvoiceRepository,
            AgentStreamServiceImpl agentStreamService,
            com.vpn.server.service.BillingService billingService,
            com.vpn.server.service.RenewalNotifier renewalNotifier
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.cryptoInvoiceRepository = cryptoInvoiceRepository;
        this.agentStreamService = agentStreamService;
        this.billingService = billingService;
        this.renewalNotifier = renewalNotifier;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    @Transactional
    public void runEnforcement() {
        Instant now = Instant.now();
        boolean stateChanged = false;

        // 1. Process time-expired subscriptions
        List<Subscription> expiredSubs = subscriptionRepository.findExpiredSubscriptions(now);
        for (Subscription sub : expiredSubs) {
            boolean renewed = false;
            if (Boolean.TRUE.equals(sub.getAutoRenew()) && sub.getTariff() != null && !"trial".equalsIgnoreCase(sub.getTariff().getId())) {
                try {
                    // A scheduled move to a cheaper plan takes effect here.
                    billingService.purchaseOrRenewSubscription(
                            sub.getUser().getId(),
                            sub.getRenewalTariff().getId(),
                            Boolean.TRUE.equals(sub.getIsAnnual())
                    );
                    renewed = true;
                    log.info("Subscription {} for user {} successfully auto-renewed", sub.getId(), sub.getUser().getId());
                } catch (com.vpn.server.service.InsufficientBalanceException e) {
                    log.info("Auto-renewal failed for user {} (sub {}): {}", sub.getUser().getId(), sub.getId(), e.getMessage());
                    renewalNotifier.notifyRenewalFailed(sub, e);
                } catch (Exception e) {
                    log.info("Auto-renewal failed for user {} (sub {}): {}", sub.getUser().getId(), sub.getId(), e.getMessage());
                }
            }

            sub.setStatus("EXPIRED");
            subscriptionRepository.save(sub);
            if (!renewed) {
                stateChanged = true;
                log.info("Subscription {} for user {} expired without renewal (period ended at {})",
                        sub.getId(), sub.getUser().getId(), sub.getCurrentPeriodEnd());
            }
        }

        // 2. Process quota-exhausted subscriptions
        List<Subscription> exceededSubs = subscriptionRepository.findQuotaExceededSubscriptions();
        for (Subscription sub : exceededSubs) {
            sub.setStatus("EXHAUSTED");
            subscriptionRepository.save(sub);
            stateChanged = true;
            log.info("Subscription {} for user {} exhausted quota: {}/{} bytes",
                    sub.getId(), sub.getUser().getId(), sub.getTrafficUsedBytes(), sub.getTrafficLimitBytes());
        }

        // 3. Revert admin-granted temporary tariff overrides once their window
        // has passed — restores the real billing tariff and the traffic limit
        // it had before the override was granted (see AdminController#grantTemporaryTariff).
        List<Subscription> expiredOverrides = subscriptionRepository.findExpiredTariffOverrides(now);
        for (Subscription sub : expiredOverrides) {
            log.info("Temporary tariff override for subscription {} (user {}) expired, reverting {} -> {}",
                    sub.getId(), sub.getUser().getId(), sub.getOverrideTariff().getId(), sub.getTariff().getId());
            if (sub.getOverridePreviousTrafficLimitBytes() != null) {
                sub.setTrafficLimitBytes(sub.getOverridePreviousTrafficLimitBytes());
            }
            sub.setOverrideTariff(null);
            sub.setOverrideExpiresAt(null);
            sub.setOverridePreviousTrafficLimitBytes(null);
            subscriptionRepository.save(sub);
            stateChanged = true;
        }

        // 3b. Warn ahead about renewals the balance won't cover
        renewalNotifier.remindUpcomingShortfalls(now);

        // 4. Mark expired crypto invoices
        List<CryptoInvoice> expiredInvoices = cryptoInvoiceRepository.findByStatusAndExpiresAtBefore("PENDING", now);
        for (CryptoInvoice invoice : expiredInvoices) {
            invoice.setStatus("EXPIRED");
            cryptoInvoiceRepository.save(invoice);
            log.debug("Crypto invoice {} expired", invoice.getId());
        }

        // 5. Purge invoices that were never paid, long enough ago that
        // re-showing "where to send" (see billing history UI) is no longer
        // useful — keeps the history list from accumulating dead rows forever.
        Instant staleCutoff = now.minus(STALE_INVOICE_RETENTION_DAYS, ChronoUnit.DAYS);
        cryptoInvoiceRepository.deleteByStatusAndExpiresAtBefore("EXPIRED", staleCutoff);

        // If any subscription state changed, trigger sync to all connected node agents
        if (stateChanged) {
            log.info("Subscription state changes detected; pushing updated config sync to all nodes");
            agentStreamService.pushConfigSyncToAll();
        }
    }
}
