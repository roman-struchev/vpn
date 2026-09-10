package com.vpn.server;

import com.vpn.server.controller.UserController;
import com.vpn.server.entity.BalanceEntry;
import com.vpn.server.entity.CryptoInvoice;
import com.vpn.server.entity.Device;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.CryptoInvoiceRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.BillingService;
import com.vpn.server.service.DeviceManagementService;
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
    private DeviceManagementService deviceManagementService;

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
                exportService,
                deviceManagementService
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

    @Test
    void testGetDevices() {
        Device d1 = new Device();
        d1.setId(101L);
        d1.setDeviceName("iPhone 15");

        when(deviceManagementService.getUserDevices(10L)).thenReturn(List.of(d1));

        ResponseEntity<List<Device>> res = userController.getDevices(auth);
        assertEquals(200, res.getStatusCode().value());
        assertEquals(1, res.getBody().size());
        assertEquals("iPhone 15", res.getBody().get(0).getDeviceName());
    }

    @Test
    void testAddDeviceSuccess() {
        Device d = new Device();
        d.setId(102L);
        d.setDeviceName("MacBook Pro");
        d.setPlatform("MACOS");

        when(deviceManagementService.addDevice(eq(10L), eq("MacBook Pro"), eq("MACOS"))).thenReturn(d);

        Map<String, String> req = Map.of("deviceName", "MacBook Pro", "platform", "MACOS");
        ResponseEntity<?> res = userController.addDevice(auth, req);
        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals("CREATED", body.get("status"));
        assertEquals(102L, body.get("deviceId"));
    }

    @Test
    void testDeleteDeviceSuccess() {
        doNothing().when(deviceManagementService).deleteDevice(10L, 102L);

        ResponseEntity<?> res = userController.deleteDevice(auth, 102L);
        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals("REVOKED", body.get("status"));
        assertEquals(102L, body.get("deviceId"));
    }

    @Test
    void testClaimTransactionSuccess() {
        BalanceEntry entry = new BalanceEntry();
        entry.setAmountUsdtMicro(5_000_000L);
        entry.setBalanceAfterMicro(8_000_000L);
        entry.setReferenceId("0xabc");

        when(billingService.claimTransaction(eq(10L), eq("TRON"), eq("0xabc"), eq(5_000_000L)))
                .thenReturn(entry);

        Map<String, Object> req = Map.of("chain", "TRON", "txHash", "0xabc", "amountMicro", 5_000_000L);
        ResponseEntity<?> res = userController.claimTransaction(auth, req);
        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals("CLAIMED", body.get("status"));
        assertEquals(5_000_000L, body.get("amountMicro"));
        assertEquals("0xabc", body.get("txHash"));
    }
}
