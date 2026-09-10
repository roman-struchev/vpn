package com.vpn.server;

import com.vpn.server.entity.*;
import com.vpn.server.repository.*;
import com.vpn.server.service.BillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

        // Delta step must be in [1..999] * 1000 micro-units
        assertTrue(invoice.getDeltaStepMicro() >= 1000 && invoice.getDeltaStepMicro() <= 999_000);
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
    void testPurchaseSubscriptionDeductsBalanceAndCreatesSub() {
        User user = new User();
        user.setId(3L);
        user.setBalanceUsdtMicro(20_000_000L); // 20 USDT

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setName("Pro");
        pro.setMonthlyPriceUsdtMicro(2_000_000L); // $2
        pro.setAnnualPriceUsdtMicro(19_200_000L); // $19.2 (-20%)
        pro.setTrafficQuotaBytes(107374182400L); // 100 GB

        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        when(tariffRepository.findById("pro")).thenReturn(Optional.of(pro));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        // Buy annual Pro
        Subscription sub = billingService.purchaseOrRenewSubscription(3L, "pro", true);

        assertNotNull(sub);
        assertTrue(sub.getIsAnnual());
        assertEquals(107374182400L, sub.getTrafficLimitBytes());
        // Balance after 19.2 USDT deduction from 20 USDT: 0.8 USDT (800,000 micro)
        assertEquals(800_000L, user.getBalanceUsdtMicro());
        verify(balanceEntryRepository, times(1)).save(any(BalanceEntry.class));
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
}
