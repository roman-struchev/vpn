package com.vpn.server;

import com.vpn.server.entity.CryptoInvoice;
import com.vpn.server.entity.User;
import com.vpn.server.repository.CryptoInvoiceRepository;
import com.vpn.server.service.BillingService;
import com.vpn.server.service.BlockchainPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockchainPaymentServiceTest {

    @Mock
    private CryptoInvoiceRepository cryptoInvoiceRepository;

    @Mock
    private BillingService billingService;

    private BlockchainPaymentService blockchainPaymentService;

    @BeforeEach
    void setUp() {
        blockchainPaymentService = new BlockchainPaymentService(
                cryptoInvoiceRepository,
                billingService
        );
    }

    @Test
    void testProcessIncomingDepositMatched() {
        User user = new User();
        user.setId(5L);

        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setId(42L);
        invoice.setUser(user);
        invoice.setStatus("PENDING");
        invoice.setExpectedAmountUsdtMicro(1_005_000L);
        invoice.setToleranceMinMicro(1_004_600L);
        invoice.setToleranceMaxMicro(1_005_400L);

        CryptoInvoice creditedInvoice = new CryptoInvoice();
        creditedInvoice.setId(42L);
        creditedInvoice.setUser(user);
        creditedInvoice.setStatus("PAID");
        creditedInvoice.setActualAmountUsdtMicro(1_004_990L);

        when(cryptoInvoiceRepository.findPendingMatchingInvoice(
                eq("TRC20"), eq("TAddr123"), eq(1_004_990L), any(Instant.class)
        )).thenReturn(List.of(invoice));

        when(billingService.creditInvoicePayment(42L, 1_004_990L, "txHash123"))
                .thenReturn(creditedInvoice);

        CryptoInvoice result = blockchainPaymentService.processIncomingDeposit(
                "TRC20", "TAddr123", 1_004_990L, "txHash123"
        );

        assertNotNull(result);
        assertEquals("PAID", result.getStatus());
        assertEquals(1_004_990L, result.getActualAmountUsdtMicro());
        verify(billingService).creditInvoicePayment(42L, 1_004_990L, "txHash123");
    }

    @Test
    void testProcessIncomingDepositUnmatched() {
        when(cryptoInvoiceRepository.findPendingMatchingInvoice(
                eq("TRC20"), eq("TAddr123"), eq(999_999L), any(Instant.class)
        )).thenReturn(Collections.emptyList());

        CryptoInvoice result = blockchainPaymentService.processIncomingDeposit(
                "TRC20", "TAddr123", 999_999L, "unknownTx"
        );

        assertNull(result);
        verify(billingService, never()).creditInvoicePayment(any(), any(), any());
    }
}
