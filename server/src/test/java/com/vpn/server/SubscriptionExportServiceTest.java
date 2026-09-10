package com.vpn.server;

import com.vpn.server.entity.*;
import com.vpn.server.repository.*;
import com.vpn.server.service.SubscriptionExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SubscriptionExportServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private DeviceRepository deviceRepository;
    private NodeRepository nodeRepository;
    private DeviceNodeKeyRepository deviceNodeKeyRepository;
    private SubscriptionExportService exportService;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        nodeRepository = mock(NodeRepository.class);
        deviceNodeKeyRepository = mock(DeviceNodeKeyRepository.class);

        exportService = new SubscriptionExportService(
                subscriptionRepository,
                deviceRepository,
                nodeRepository,
                deviceNodeKeyRepository
        );
    }

    @Test
    void testExportPaidSubscriptionProducesVlessBase64() {
        User user = new User();
        user.setId(10L);

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setName("Pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Device device = new Device();
        device.setId(100L);
        device.setUser(user);
        device.setDeviceName("Phone");
        device.setIsActive(true);

        Node node = new Node();
        node.setId(1L);
        node.setHostname("ams-01.vpn.internal");
        node.setPublicIp("198.51.100.22");
        node.setRegion("nl-ams");
        node.setRealityPublicKey("publicKey123");
        node.setRealityShortIds(new String[]{"abcdef0123456789"});
        node.setStatus("ONLINE");
        node.setPool("paid");

        UUID keyUuid = UUID.randomUUID();
        DeviceNodeKey key = new DeviceNodeKey(device, node, keyUuid);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(10L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(10L)).thenReturn(List.of(device));
        when(nodeRepository.findByPoolAndStatus("paid", "ONLINE")).thenReturn(List.of(node));
        when(deviceNodeKeyRepository.findByDeviceIdAndNodeId(100L, 1L)).thenReturn(Optional.of(key));

        String base64Output = exportService.exportVlessSubscription(10L);
        assertNotNull(base64Output);

        String decoded = new String(Base64.getDecoder().decode(base64Output), StandardCharsets.UTF_8);
        assertTrue(decoded.startsWith("vless://"));
        assertTrue(decoded.contains(keyUuid.toString()));
        assertTrue(decoded.contains("198.51.100.22:443"));
        assertTrue(decoded.contains("type=xhttp"));
        assertTrue(decoded.contains("security=reality"));
    }

    @Test
    void testExportTrialSubscriptionDenied() {
        User user = new User();
        user.setId(20L);

        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setName("Trial");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(trial);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(7, ChronoUnit.DAYS));

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(20L, "ACTIVE"))
                .thenReturn(Optional.of(sub));

        // PLAN.md §1: "subscription-ссылка vless:// — только платным" — this
        // restriction targets the *public*, token-based export for arbitrary
        // third-party clients.
        assertThrows(IllegalStateException.class, () ->
                exportService.exportVlessSubscription(20L));
    }

    @Test
    void testExportForOwnAppAllowsTrialOnceADeviceExists() {
        // Regression: exportVlessLinksForOwnApp is what Android/Desktop and the
        // web dashboard's "copy link" button actually call (UserController.
        // getSubscriptionLinks) — the "paid plans only" restriction belongs only
        // to the public token-based export, not a logged-in user fetching their
        // own credentials to connect through the official app. Before this was
        // split, a trial user's own app could never connect at all — reported
        // live: "Failed to load VPN profile ApiError: Subscription link export
        // is available for paid plans only" from the Desktop client.
        User user = new User();
        user.setId(21L);

        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setName("Trial");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(trial);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(3, ChronoUnit.DAYS));

        Device device = new Device();
        device.setId(200L);
        device.setUser(user);
        device.setDeviceName("MacBook Pro");
        device.setIsActive(true);

        Node node = new Node();
        node.setId(2L);
        node.setHostname("trial-01.vpn.internal");
        node.setPublicIp("198.51.100.30");
        node.setRegion("nl-ams");
        node.setStatus("ONLINE");
        node.setPool("trial");

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(21L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(21L)).thenReturn(List.of(device));
        when(nodeRepository.findByPoolAndStatus("paid", "ONLINE")).thenReturn(List.of());
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(node));
        when(deviceNodeKeyRepository.findByDeviceIdAndNodeId(eq(200L), eq(2L))).thenReturn(Optional.empty());
        when(deviceNodeKeyRepository.save(any(DeviceNodeKey.class))).thenAnswer(i -> i.getArgument(0));

        List<String> links = exportService.exportVlessLinksForOwnApp(21L);

        assertEquals(1, links.size());
        assertTrue(links.get(0).startsWith("vless://"));
    }

    @Test
    void testExportForOwnAppReturnsEmptyWithoutAutoCreatingAPlaceholderDevice() {
        // Regression: the public export path's "auto-create a Primary Device if
        // none exists" convenience must NOT apply to the own-app path — it used
        // to silently consume a trial account's one-and-only device slot the
        // instant the dashboard loaded (before the user ever added or
        // auto-registered a real device), reproduced live via the e2e suite.
        User user = new User();
        user.setId(22L);

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setName("Pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(22L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(22L)).thenReturn(List.of());

        List<String> links = exportService.exportVlessLinksForOwnApp(22L);

        assertTrue(links.isEmpty());
        verify(deviceRepository, never()).save(any());
    }
}
