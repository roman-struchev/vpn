package com.vpn.server;

import com.vpn.server.entity.*;
import com.vpn.server.repository.*;
import com.vpn.server.service.NodeManagementService;
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
    private NodeManagementService nodeManagementService;
    private SubscriptionExportService exportService;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        nodeRepository = mock(NodeRepository.class);
        deviceNodeKeyRepository = mock(DeviceNodeKeyRepository.class);
        nodeManagementService = mock(NodeManagementService.class);

        // Real nodes already have keys in these tests; stub the backfill call as
        // a passthrough so it doesn't clobber the fixtures' realityPublicKey.
        when(nodeManagementService.ensureRealityKeyMaterial(any(Node.class)))
                .thenAnswer(i -> i.getArgument(0));

        exportService = new SubscriptionExportService(
                subscriptionRepository,
                deviceRepository,
                nodeRepository,
                deviceNodeKeyRepository,
                nodeManagementService
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
    void testExportForOwnAppWithRegionFiltersToThatRegionOnly() {
        User user = new User();
        user.setId(23L);

        Tariff pro = new Tariff();
        pro.setId("pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Device device = new Device();
        device.setId(300L);
        device.setUser(user);
        device.setIsActive(true);

        Node amsNode = new Node();
        amsNode.setId(3L);
        amsNode.setHostname("ams-01.vpn.internal");
        amsNode.setPublicIp("198.51.100.40");
        amsNode.setRegion("nl-ams");
        amsNode.setStatus("ONLINE");
        amsNode.setPool("paid");

        Node laxNode = new Node();
        laxNode.setId(4L);
        laxNode.setHostname("lax-01.vpn.internal");
        laxNode.setPublicIp("198.51.100.41");
        laxNode.setRegion("us-lax");
        laxNode.setStatus("ONLINE");
        laxNode.setPool("paid");

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(23L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(23L)).thenReturn(List.of(device));
        when(nodeRepository.findByPoolAndStatus("paid", "ONLINE")).thenReturn(List.of(amsNode, laxNode));
        when(deviceNodeKeyRepository.findByDeviceIdAndNodeId(eq(300L), eq(3L)))
                .thenReturn(Optional.of(new DeviceNodeKey(device, amsNode, UUID.randomUUID())));

        SubscriptionExportService.RegionScopedLinks result = exportService.exportVlessLinksForOwnApp(23L, "nl-ams");

        assertTrue(result.requestedRegionAvailable());
        assertEquals(1, result.links().size());
        assertTrue(result.links().get(0).contains("198.51.100.40"));
    }

    @Test
    void testExportForOwnAppWithUnavailableRegionFallsBackToAllNodes() {
        User user = new User();
        user.setId(24L);

        Tariff pro = new Tariff();
        pro.setId("pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Device device = new Device();
        device.setId(400L);
        device.setUser(user);
        device.setIsActive(true);

        Node amsNode = new Node();
        amsNode.setId(5L);
        amsNode.setHostname("ams-02.vpn.internal");
        amsNode.setPublicIp("198.51.100.50");
        amsNode.setRegion("nl-ams");
        amsNode.setStatus("ONLINE");
        amsNode.setPool("paid");

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(24L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(24L)).thenReturn(List.of(device));
        when(nodeRepository.findByPoolAndStatus("paid", "ONLINE")).thenReturn(List.of(amsNode));
        when(deviceNodeKeyRepository.findByDeviceIdAndNodeId(eq(400L), eq(5L)))
                .thenReturn(Optional.of(new DeviceNodeKey(device, amsNode, UUID.randomUUID())));

        // "us-lax" has no online node — should fall back to the full (nl-ams-only,
        // in this fixture) node list rather than returning zero links.
        SubscriptionExportService.RegionScopedLinks result = exportService.exportVlessLinksForOwnApp(24L, "us-lax");

        assertFalse(result.requestedRegionAvailable());
        assertEquals(1, result.links().size());
    }

    @Test
    void testGetAvailableRegionsAggregatesLoadPerRegion() {
        User user = new User();
        user.setId(25L);

        Tariff pro = new Tariff();
        pro.setId("pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node amsLow = new Node();
        amsLow.setId(6L);
        amsLow.setRegion("nl-ams");
        amsLow.setStatus("ONLINE");
        amsLow.setPool("paid");
        amsLow.setCpuPercent(new java.math.BigDecimal("20.0"));
        amsLow.setActiveConnections(10);

        Node amsHigh = new Node();
        amsHigh.setId(7L);
        amsHigh.setRegion("nl-ams");
        amsHigh.setStatus("ONLINE");
        amsHigh.setPool("paid");
        amsHigh.setCpuPercent(new java.math.BigDecimal("30.0"));
        amsHigh.setActiveConnections(30);

        Node laxBusy = new Node();
        laxBusy.setId(8L);
        laxBusy.setRegion("us-lax");
        laxBusy.setStatus("ONLINE");
        laxBusy.setPool("paid");
        laxBusy.setCpuPercent(new java.math.BigDecimal("90.0"));
        laxBusy.setActiveConnections(200);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(25L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByPoolAndStatus("paid", "ONLINE")).thenReturn(List.of(amsLow, amsHigh, laxBusy));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(25L);

        assertEquals(2, regions.size());
        SubscriptionExportService.RegionSummary ams = regions.stream()
                .filter(r -> r.region().equals("nl-ams")).findFirst().orElseThrow();
        SubscriptionExportService.RegionSummary lax = regions.stream()
                .filter(r -> r.region().equals("us-lax")).findFirst().orElseThrow();

        assertEquals(2, ams.nodeCount());
        assertEquals(25.0, ams.avgCpuPercent());
        assertEquals("LOW", ams.loadLevel());

        assertEquals(1, lax.nodeCount());
        assertEquals(90.0, lax.avgCpuPercent());
        assertEquals("HIGH", lax.loadLevel());
    }

    @Test
    void testGetAvailableRegionsWithZeroConnectionsNeverReportsHigh() {
        // Regression for the "idle node shown as loaded" bug: a node with zero
        // active connections has nothing to do with VPN traffic, but stale/
        // unrelated host CPU (OS housekeeping, monitoring agents, a residual
        // reading right after some unrelated burst) could still read fairly
        // high. Zero connections should cap the result at MEDIUM, never HIGH.
        User user = new User();
        user.setId(27L);

        Tariff pro = new Tariff();
        pro.setId("pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node idleButCpuHigh = new Node();
        idleButCpuHigh.setId(9L);
        idleButCpuHigh.setRegion("eu-fra");
        idleButCpuHigh.setStatus("ONLINE");
        idleButCpuHigh.setPool("paid");
        idleButCpuHigh.setCpuPercent(new java.math.BigDecimal("90.0"));
        idleButCpuHigh.setActiveConnections(0);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(27L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByPoolAndStatus("paid", "ONLINE")).thenReturn(List.of(idleButCpuHigh));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(27L);

        SubscriptionExportService.RegionSummary fra = regions.stream()
                .filter(r -> r.region().equals("eu-fra")).findFirst().orElseThrow();
        assertEquals(0L, fra.avgActiveConnections());
        assertEquals("MEDIUM", fra.loadLevel());
    }

    @Test
    void testGetAvailableRegionsNoActiveSubscriptionThrows() {
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(26L, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> exportService.getAvailableRegions(26L));
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
