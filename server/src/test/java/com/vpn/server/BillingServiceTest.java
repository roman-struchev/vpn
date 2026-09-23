package com.vpn.server;

import com.vpn.server.entity.*;
import com.vpn.server.repository.*;
import com.vpn.server.service.BillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BillingServiceTest {

    private UserRepository userRepository;
    private TariffRepository tariffRepository;
    private SubscriptionRepository subscriptionRepository;
    private BalanceEntryRepository balanceEntryRepository;
    private CryptoInvoiceRepository cryptoInvoiceRepository;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tariffRepository = mock(TariffRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        balanceEntryRepository = mock(BalanceEntryRepository.class);
        cryptoInvoiceRepository = mock(CryptoInvoiceRepository.class);

        billingService = new BillingService(
                userRepository,
                tariffRepository,
                subscriptionRepository,
                balanceEntryRepository,
                cryptoInvoiceRepository
        );
    }

    @Test
    void testCreateInvoiceCalculatesDeltaAndTolerance() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cryptoInvoiceRepository.save(any(CryptoInvoice.class))).thenAnswer(i -> i.getArgument(0));

        long baseAmount = 10_000_000L; // 10 USDT
        CryptoInvoice invoice = billingService.createInvoice(1L, "TRON", baseAmount, "TReceivingAddress123");

        assertNotNull(invoice);
        assertEquals("TRON", invoice.getChain());
        assertEquals("USDT", invoice.getToken());
        assertEquals(baseAmount, invoice.getBaseAmountUsdtMicro());

        // Delta step must be in [1..99] * 1000 micro-units (max +$0.099 surcharge —
        // see BillingService.createInvoice for why this was shrunk from [1..999]).
        assertTrue(invoice.getDeltaStepMicro() >= 1000 && invoice.getDeltaStepMicro() <= 99_000);
        assertEquals(baseAmount + invoice.getDeltaStepMicro(), invoice.getExpectedAmountUsdtMicro());
        assertEquals(invoice.getExpectedAmountUsdtMicro() - 400, invoice.getToleranceMinMicro());
        assertEquals(invoice.getExpectedAmountUsdtMicro() + 400, invoice.getToleranceMaxMicro());
    }

    @Test
    void testCreditInvoicePaymentCreditsActualAmount() {
        User user = new User();
        user.setId(2L);
        user.setBalanceUsdtMicro(5_000_000L); // 5 USDT

        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setId(50L);
        invoice.setUser(user);
        invoice.setChain("TRON");
        invoice.setStatus("PENDING");

        when(cryptoInvoiceRepository.findById(50L)).thenReturn(Optional.of(invoice));

        long actualReceived = 10_005_000L; // 10.005 USDT
        billingService.creditInvoicePayment(50L, actualReceived, "0xhash123");

        assertEquals("PAID", invoice.getStatus());
        assertEquals(15_005_000L, user.getBalanceUsdtMicro());
        verify(balanceEntryRepository, times(1)).save(any(BalanceEntry.class));
    }

    @Test
    void testCreditInvoicePaymentAppliesReferralRewardsOnFirstDeposit() {
        User referrer = new User();
        referrer.setId(7L);
        referrer.setBalanceUsdtMicro(0L);

        User user = new User();
        user.setId(8L);
        user.setBalanceUsdtMicro(0L);
        user.setReferredBy(referrer);

        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setId(51L);
        invoice.setUser(user);
        invoice.setChain("TRON");
        invoice.setStatus("PENDING");

        when(cryptoInvoiceRepository.findById(51L)).thenReturn(Optional.of(invoice));
        // Only the DEPOSIT entry saved just above exists so far -> this is the user's first deposit.
        when(balanceEntryRepository.countByUserIdAndType(8L, "DEPOSIT")).thenReturn(1L);

        billingService.creditInvoicePayment(51L, 10_000_000L, "0xhash456");

        // Referee: 10 USDT deposit + 10% welcome bonus = 11 USDT
        assertEquals(11_000_000L, user.getBalanceUsdtMicro());
        // Referrer: 15% of the 10 USDT deposit
        assertEquals(1_500_000L, referrer.getBalanceUsdtMicro());
        // 1 deposit entry + 1 referrer bonus entry + 1 referee welcome bonus entry
        verify(balanceEntryRepository, times(3)).save(any(BalanceEntry.class));
    }

    @Test
    void testCreditInvoicePaymentSkipsWelcomeBonusAfterFirstDeposit() {
        User referrer = new User();
        referrer.setId(9L);
        referrer.setBalanceUsdtMicro(0L);

        User user = new User();
        user.setId(11L);
        user.setBalanceUsdtMicro(0L);
        user.setReferredBy(referrer);

        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setId(52L);
        invoice.setUser(user);
        invoice.setChain("TRON");
        invoice.setStatus("PENDING");

        when(cryptoInvoiceRepository.findById(52L)).thenReturn(Optional.of(invoice));
        // This user already had a prior deposit before this one.
        when(balanceEntryRepository.countByUserIdAndType(11L, "DEPOSIT")).thenReturn(2L);

        billingService.creditInvoicePayment(52L, 10_000_000L, "0xhash789");

        // Referee gets only the raw deposit, no welcome bonus this time.
        assertEquals(10_000_000L, user.getBalanceUsdtMicro());
        assertEquals(1_500_000L, referrer.getBalanceUsdtMicro());
        // 1 deposit entry + 1 referrer bonus entry, no welcome bonus entry
        verify(balanceEntryRepository, times(2)).save(any(BalanceEntry.class));
    }

    @Test
    void testPurchaseSubscriptionDeductsBalanceAndCreatesSub() {
        User user = new User();
        user.setId(3L);
        user.setBalanceUsdtMicro(25_000_000L); // 25 USDT

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setName("Pro");
        pro.setMonthlyPriceUsdtMicro(2_000_000L); // $2
        pro.setAnnualPriceUsdtMicro(20_000_000L); // $20, rounded (~-17%, see docs/PLAN.md §2)
        pro.setTrafficQuotaBytes(107374182400L); // 100 GB

        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("pro")).thenReturn(Optional.of(pro));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        // Buy annual Pro
        Subscription sub = billingService.purchaseOrRenewSubscription(3L, "pro", true);

        assertNotNull(sub);
        assertTrue(sub.getIsAnnual());
        assertEquals(107374182400L, sub.getTrafficLimitBytes());
        // Balance after 20 USDT deduction from 25 USDT: 5 USDT (5,000,000 micro)
        assertEquals(5_000_000L, user.getBalanceUsdtMicro());
        verify(balanceEntryRepository, times(1)).save(any(BalanceEntry.class));
    }

    @Test
    void testFirstTrialActivationSucceeds() {
        User user = new User();
        user.setId(9L);

        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setName("Пробный");
        trial.setMonthlyPriceUsdtMicro(0L);
        trial.setAnnualPriceUsdtMicro(0L);
        trial.setTrafficQuotaBytes(1_073_741_824L);

        when(userRepository.findById(9L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("trial")).thenReturn(Optional.of(trial));
        when(subscriptionRepository.existsByUserIdAndTariffId(9L, "trial")).thenReturn(false);
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        Subscription sub = billingService.purchaseOrRenewSubscription(9L, "trial", false);

        assertNotNull(sub);
        assertEquals(1_073_741_824L, sub.getTrafficLimitBytes());
    }

    @Test
    void testTrialCannotBeActivatedTwiceOnSameAccount() {
        // Regression: price=0 used to skip straight to creating/extending the
        // subscription with no other check, so a user could click "renew" on
        // the trial tariff forever — see docs/PLAN.md §2 and
        // docs/ROADMAP_PROGRESS.md "Пост-Фаза-10". Any prior subscription row
        // for this tariff (active, expired, or cancelled) must block reuse.
        User user = new User();
        user.setId(9L);

        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setMonthlyPriceUsdtMicro(0L);
        trial.setAnnualPriceUsdtMicro(0L);

        when(userRepository.findById(9L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("trial")).thenReturn(Optional.of(trial));
        when(subscriptionRepository.existsByUserIdAndTariffId(9L, "trial")).thenReturn(true);

        assertThrows(IllegalStateException.class, () ->
                billingService.purchaseOrRenewSubscription(9L, "trial", false));

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void testPurchaseSubscriptionInsufficientBalanceThrows() {
        User user = new User();
        user.setId(4L);
        user.setBalanceUsdtMicro(500_000L); // 0.5 USDT

        Tariff basic = new Tariff();
        basic.setId("basic");
        basic.setMonthlyPriceUsdtMicro(1_000_000L); // $1

        when(userRepository.findById(4L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("basic")).thenReturn(Optional.of(basic));

        assertThrows(IllegalStateException.class, () ->
                billingService.purchaseOrRenewSubscription(4L, "basic", false));
    }

    @Test
    void testClaimTransactionSuccess() {
        User user = new User();
        user.setId(5L);
        user.setBalanceUsdtMicro(1_000_000L);

        when(cryptoInvoiceRepository.existsByTxHash("tx_claim_123")).thenReturn(false);
        when(balanceEntryRepository.existsByReferenceId("tx_claim_123")).thenReturn(false);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(balanceEntryRepository.save(any(BalanceEntry.class))).thenAnswer(i -> i.getArgument(0));

        BalanceEntry entry = billingService.claimTransaction(5L, "TRON", "tx_claim_123", 5_000_000L);

        assertNotNull(entry);
        assertEquals(5_000_000L, entry.getAmountUsdtMicro());
        assertEquals(6_000_000L, entry.getBalanceAfterMicro());
        assertEquals("tx_claim_123", entry.getReferenceId());
        assertEquals(6_000_000L, user.getBalanceUsdtMicro());
    }

    @Test
    void testClaimTransactionDuplicateThrows() {
        when(cryptoInvoiceRepository.existsByTxHash("tx_dup")).thenReturn(true);

        assertThrows(IllegalStateException.class, () ->
                billingService.claimTransaction(5L, "TRON", "tx_dup", 5_000_000L));
    }

    @Test
    void testCreateInvoiceUsesTronAddressForTronChain() {
        ReflectionTestUtils.setField(billingService, "defaultTronDepositAddress", "TRealTronAddress");
        ReflectionTestUtils.setField(billingService, "defaultEvmDepositAddress", "0xRealEvmAddress");

        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cryptoInvoiceRepository.save(any(CryptoInvoice.class))).thenAnswer(i -> i.getArgument(0));

        CryptoInvoice invoice = billingService.createInvoice(1L, "TRON", 10_000_000L);

        assertEquals("TRealTronAddress", invoice.getRecipientAddress());
    }

    @Test
    void testCreateInvoiceUsesEvmAddressForEthereumChain() {
        // Regression test: this overload used to always fall back to the TRON
        // (base58) address regardless of chain, producing an invoice no EVM
        // wallet could actually pay — see docs/ROADMAP_PROGRESS.md "Пост-Фаза-10".
        ReflectionTestUtils.setField(billingService, "defaultTronDepositAddress", "TRealTronAddress");
        ReflectionTestUtils.setField(billingService, "defaultEvmDepositAddress", "0xRealEvmAddress");

        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cryptoInvoiceRepository.save(any(CryptoInvoice.class))).thenAnswer(i -> i.getArgument(0));

        CryptoInvoice invoice = billingService.createInvoice(1L, "ETHEREUM", 10_000_000L);

        assertEquals("0xRealEvmAddress", invoice.getRecipientAddress());
    }

    @Test
    void testCreateInvoiceRejectsUnconfiguredEvmDepositAddress() {
        ReflectionTestUtils.setField(billingService, "defaultEvmDepositAddress", "");

        assertThrows(IllegalStateException.class, () ->
                billingService.createInvoice(1L, "BASE", 10_000_000L));
    }

    @Test
    void testCreditInvoicePaymentPreventsDoubleCreditingWhenAlreadyCredited() {
        User user = new User();
        user.setId(2L);
        user.setBalanceUsdtMicro(5_000_000L);

        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setId(52L);
        invoice.setUser(user);
        invoice.setChain("TRON");
        invoice.setStatus("PENDING");

        when(cryptoInvoiceRepository.findById(52L)).thenReturn(Optional.of(invoice));
        when(cryptoInvoiceRepository.save(any(CryptoInvoice.class))).thenAnswer(i -> i.getArgument(0));
        when(balanceEntryRepository.existsByReferenceId("0xduplicate_hash")).thenReturn(true);

        CryptoInvoice result = billingService.creditInvoicePayment(52L, 10_000_000L, "0xduplicate_hash");

        assertEquals("PAID", result.getStatus());
        assertEquals("0xduplicate_hash", result.getTxHash());
        // Balance remains unchanged
        assertEquals(5_000_000L, user.getBalanceUsdtMicro());
        verify(balanceEntryRepository, never()).save(any(BalanceEntry.class));
    }

    @Test
    void testPurchaseTrialSubscriptionHasNoRealTimeLimitAndNoAutoRenew() {
        User user = new User();
        user.setId(5L);
        user.setBalanceUsdtMicro(0L);

        Tariff trialTariff = new Tariff();
        trialTariff.setId("trial");
        trialTariff.setMonthlyPriceUsdtMicro(0L);
        trialTariff.setTrafficQuotaBytes(20_000_000_000L);

        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("trial")).thenReturn(Optional.of(trialTariff));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(eq(5L), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        Subscription sub = billingService.purchaseOrRenewSubscription(5L, "trial", false);

        assertNotNull(sub);
        assertFalse(sub.getAutoRenew());
        // No more real time limit for trial — only the traffic quota caps it
        // (see BillingService.NO_EXPIRY_DAYS / Subscription#hasNoExpiry).
        assertTrue(sub.hasNoExpiry());
    }

    @Test
    void testClaimTransactionMarksPendingInvoicePaid() {
        User user = new User();
        user.setId(10L);
        user.setBalanceUsdtMicro(1_000_000L);

        CryptoInvoice pendingInvoice = new CryptoInvoice();
        pendingInvoice.setId(99L);
        pendingInvoice.setUser(user);
        pendingInvoice.setChain("TRON");
        pendingInvoice.setStatus("PENDING");

        when(cryptoInvoiceRepository.existsByTxHash("0xclaim123")).thenReturn(false);
        when(balanceEntryRepository.existsByReferenceId("0xclaim123")).thenReturn(false);
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(cryptoInvoiceRepository.findByUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(pendingInvoice));
        when(balanceEntryRepository.save(any(BalanceEntry.class))).thenAnswer(i -> i.getArgument(0));

        BalanceEntry entry = billingService.claimTransaction(10L, "TRON", "0xclaim123", 5_000_000L);

        assertNotNull(entry);
        assertEquals(6_000_000L, user.getBalanceUsdtMicro());
        assertEquals("PAID", pendingInvoice.getStatus());
        assertEquals("0xclaim123", pendingInvoice.getTxHash());
        verify(cryptoInvoiceRepository).save(pendingInvoice);
    }

    @Test
    void testUpgradeSubscriptionSupersedesActivePlanAndStartsImmediately() {
        User user = new User();
        user.setId(11L);
        user.setBalanceUsdtMicro(10_000_000L); // 10 USDT

        Tariff trialTariff = new Tariff();
        trialTariff.setId("trial");
        trialTariff.setName("Trial");

        Tariff proTariff = new Tariff();
        proTariff.setId("pro");
        proTariff.setName("Pro");
        proTariff.setMonthlyPriceUsdtMicro(2_000_000L);
        proTariff.setTrafficQuotaBytes(100L * 1024 * 1024 * 1024);

        Subscription activeTrial = new Subscription();
        activeTrial.setId(55L);
        activeTrial.setUser(user);
        activeTrial.setTariff(trialTariff);
        activeTrial.setStatus("ACTIVE");
        activeTrial.setCurrentPeriodStart(Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        activeTrial.setCurrentPeriodEnd(Instant.now().plus(2, java.time.temporal.ChronoUnit.DAYS));

        when(userRepository.findById(11L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("pro")).thenReturn(Optional.of(proTariff));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(11L, "ACTIVE"))
                .thenReturn(Optional.of(activeTrial));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        Subscription upgraded = billingService.purchaseOrRenewSubscription(11L, "pro", false);

        assertNotNull(upgraded);
        assertEquals("ACTIVE", upgraded.getStatus());
        assertEquals("pro", upgraded.getTariff().getId());
        assertEquals("SUPERSEDED", activeTrial.getStatus());
        assertFalse(activeTrial.getAutoRenew());
        // Upgraded subscription starts immediately, not at activeTrial's period end
        assertTrue(upgraded.getCurrentPeriodStart().isBefore(activeTrial.getCurrentPeriodEnd()));
        assertEquals(8_000_000L, user.getBalanceUsdtMicro());
    }

    @Test
    void testSameTariffRenewalExtendsPeriod() {
        User user = new User();
        user.setId(12L);
        user.setBalanceUsdtMicro(10_000_000L);

        Tariff proTariff = new Tariff();
        proTariff.setId("pro");
        proTariff.setName("Pro");
        proTariff.setMonthlyPriceUsdtMicro(2_000_000L);
        proTariff.setTrafficQuotaBytes(100L * 1024 * 1024 * 1024);

        Instant initialEnd = Instant.now().plus(10, java.time.temporal.ChronoUnit.DAYS);
        Subscription activePro = new Subscription();
        activePro.setId(56L);
        activePro.setUser(user);
        activePro.setTariff(proTariff);
        activePro.setStatus("ACTIVE");
        activePro.setCurrentPeriodEnd(initialEnd);

        when(userRepository.findById(12L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("pro")).thenReturn(Optional.of(proTariff));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(12L, "ACTIVE"))
                .thenReturn(Optional.of(activePro));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        Subscription renewed = billingService.purchaseOrRenewSubscription(12L, "pro", false);

        assertNotNull(renewed);
        assertEquals(initialEnd, renewed.getCurrentPeriodStart());
        assertEquals("ACTIVE", activePro.getStatus()); // Not superseded
    }

    private static Tariff paidTariff(String id, long monthlyMicro) {
        Tariff t = new Tariff();
        t.setId(id);
        t.setName(id);
        t.setMonthlyPriceUsdtMicro(monthlyMicro);
        t.setAnnualPriceUsdtMicro(monthlyMicro * 10);
        t.setTrafficQuotaBytes(100L * 1024 * 1024 * 1024);
        return t;
    }

    private Subscription activeSub(User user, Tariff tariff, Instant end) {
        Subscription sub = new Subscription();
        sub.setId(70L);
        sub.setUser(user);
        sub.setTariff(tariff);
        sub.setStatus("ACTIVE");
        sub.setAutoRenew(true);
        sub.setCurrentPeriodStart(Instant.now().minus(5, java.time.temporal.ChronoUnit.DAYS));
        sub.setCurrentPeriodEnd(end);
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(user.getId(), "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));
        return sub;
    }

    @Test
    void testBuyingCheaperPlanOverPaidOneIsRefusedWithoutCharging() {
        User user = new User();
        user.setId(20L);
        user.setBalanceUsdtMicro(10_000_000L);
        Tariff max = paidTariff("max", 5_000_000L);
        Tariff mid = paidTariff("mid", 3_000_000L);
        when(userRepository.findById(20L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("mid")).thenReturn(Optional.of(mid));
        Subscription current = activeSub(user, max, Instant.now().plus(20, java.time.temporal.ChronoUnit.DAYS));

        assertThrows(IllegalStateException.class, () -> billingService.purchaseOrRenewSubscription(20L, "mid", false));

        assertEquals(10_000_000L, user.getBalanceUsdtMicro());
        assertEquals("ACTIVE", current.getStatus());
        verify(balanceEntryRepository, never()).save(any());
    }

    @Test
    void testCheaperPlanIsScheduledForTheEndOfThePeriod() {
        User user = new User();
        user.setId(21L);
        user.setBalanceUsdtMicro(10_000_000L);
        Tariff max = paidTariff("max", 5_000_000L);
        Tariff mid = paidTariff("mid", 3_000_000L);
        when(tariffRepository.findById("mid")).thenReturn(Optional.of(mid));
        Instant end = Instant.now().plus(20, java.time.temporal.ChronoUnit.DAYS);
        Subscription current = activeSub(user, max, end);

        Subscription scheduled = billingService.scheduleNextTariff(21L, "mid");

        assertSame(current, scheduled);
        assertEquals("mid", current.getNextTariff().getId());
        assertEquals("max", current.getTariff().getId());
        assertEquals(end, current.getCurrentPeriodEnd());
        assertEquals("mid", current.getRenewalTariff().getId());
        assertEquals(10_000_000L, user.getBalanceUsdtMicro());

        billingService.scheduleNextTariff(21L, null);
        assertNull(current.getNextTariff());
        assertEquals("max", current.getRenewalTariff().getId());
    }

    @Test
    void testPricierPlanCannotBeScheduled() {
        User user = new User();
        user.setId(22L);
        Tariff mid = paidTariff("mid", 3_000_000L);
        Tariff max = paidTariff("max", 5_000_000L);
        when(tariffRepository.findById("max")).thenReturn(Optional.of(max));
        activeSub(user, mid, Instant.now().plus(20, java.time.temporal.ChronoUnit.DAYS));

        assertThrows(IllegalStateException.class, () -> billingService.scheduleNextTariff(22L, "max"));
    }

    @Test
    void testManualRenewalStopsTheOldRowFromAutoRenewingAgain() {
        User user = new User();
        user.setId(23L);
        user.setBalanceUsdtMicro(10_000_000L);
        Tariff max = paidTariff("max", 5_000_000L);
        Tariff mid = paidTariff("mid", 3_000_000L);
        when(userRepository.findById(23L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("max")).thenReturn(Optional.of(max));
        Subscription current = activeSub(user, max, Instant.now().plus(20, java.time.temporal.ChronoUnit.DAYS));
        current.setNextTariff(mid);

        Subscription renewed = billingService.purchaseOrRenewSubscription(23L, "max", false);

        assertFalse(current.getAutoRenew());
        assertNull(current.getNextTariff());
        assertTrue(renewed.getAutoRenew());
        assertEquals("mid", renewed.getNextTariff().getId());
        assertEquals(5_000_000L, user.getBalanceUsdtMicro());
    }

    @Test
    void testTrialCannotReplaceARunningPaidPlan() {
        User user = new User();
        user.setId(24L);
        Tariff pro = paidTariff("pro", 2_000_000L);
        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setMonthlyPriceUsdtMicro(0L);
        trial.setAnnualPriceUsdtMicro(0L);
        when(userRepository.findById(24L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("trial")).thenReturn(Optional.of(trial));
        Subscription current = activeSub(user, pro, Instant.now().plus(20, java.time.temporal.ChronoUnit.DAYS));

        assertThrows(IllegalStateException.class, () -> billingService.purchaseOrRenewSubscription(24L, "trial", false));
        assertEquals("ACTIVE", current.getStatus());
    }
}
