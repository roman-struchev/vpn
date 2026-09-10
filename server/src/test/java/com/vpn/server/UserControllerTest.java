package com.vpn.server;

import com.vpn.server.controller.UserController;
import com.vpn.server.entity.CryptoInvoice;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.CryptoInvoiceRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.BillingService;
import com.vpn.server.service.SubscriptionExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private TariffRepository tariffRepository;

    @Mock
    private BillingService billingService;

    @Mock
    private CryptoInvoiceRepository cryptoInvoiceRepository;

    @Mock
    private SubscriptionExportService exportService;

    @Mock
    private Authentication auth;

    private UserController userController;

    @BeforeEach
    void setUp() {
        userController = new UserController(
                userRepository,
                subscriptionRepository,
                tariffRepository,
                billingService,
                cryptoInvoiceRepository,
                exportService
        );
        when(auth.getPrincipal()).thenReturn(10L);
    }

    @Test
    void testGetProfile() {
        User user = new User();
        user.setId(10L);
        user.setEmail("user@example.com");
        user.setRole("USER");
        user.setBalanceUsdtMicro(5_000_000L);

        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(10L, "ACTIVE"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> res = userController.getProfile(auth);
        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals(10L, body.get("id"));
        assertEquals("user@example.com", body.get("email"));
        assertEquals(5_000_000L, body.get("balanceUsdtMicro"));
        assertEquals(false, body.get("hasActiveSubscription"));
    }

    @Test
    void testCreateInvoice() {
        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setId(123L);
        invoice.setChain("TRON");
        invoice.setRecipientAddress("TAddrSample");
        invoice.setExpectedAmountUsdtMicro(3_005_000L);
        invoice.setDeltaStepMicro(5000);
        invoice.setStatus("PENDING");
        invoice.setExpiresAt(Instant.now());

        when(billingService.createInvoice(eq(10L), eq("TRON"), eq(3_000_000L))).thenReturn(invoice);

        Map<String, Object> req = Map.of("amountMicro", 3_000_000L, "chain", "TRON");
        ResponseEntity<?> res = userController.createInvoice(auth, req);
        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals(123L, body.get("invoiceId"));
        assertEquals("TRON", body.get("chain"));
        assertEquals("TAddrSample", body.get("recipientAddress"));
        assertEquals(3_005_000L, body.get("expectedAmountUsdtMicro"));
    }

    @Test
    void testPurchaseSubscriptionSuccess() {
        Tariff tariff = new Tariff();
        tariff.setId("standard");

        Subscription sub = new Subscription();
        sub.setId(55L);
        sub.setTariff(tariff);
        sub.setCurrentPeriodEnd(Instant.now());
        sub.setTrafficLimitBytes(100_000_000_000L);

        when(billingService.purchaseOrRenewSubscription(eq(10L), eq("standard"), eq(false)))
                .thenReturn(sub);

        Map<String, Object> req = Map.of("tariffId", "standard", "isAnnual", false);
        ResponseEntity<?> res = userController.purchaseSubscription(auth, req);
        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals("SUCCESS", body.get("status"));
        assertEquals(55L, body.get("subscriptionId"));
    }

    @Test
    void testGetSubscriptionLinksSuccess() {
        when(exportService.exportVlessLinks(10L)).thenReturn(List.of(
                "vless://uuid1@1.2.3.4:443?...",
                "vless://uuid2@5.6.7.8:443?..."
        ));

        ResponseEntity<?> res = userController.getSubscriptionLinks(auth);
        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals(2, body.get("count"));
    }
}
