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
import java.util.Set;
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
        node.setAvailableToPaid(true);

        UUID keyUuid = UUID.randomUUID();
        DeviceNodeKey key = new DeviceNodeKey(device, node, keyUuid);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(10L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(10L)).thenReturn(List.of(device));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(node));
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
        node.setAvailableToTrial(true);
        node.setAvailableToPaid(true);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(21L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(21L)).thenReturn(List.of(device));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of());
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
        amsNode.setAvailableToPaid(true);

        Node laxNode = new Node();
        laxNode.setId(4L);
        laxNode.setHostname("lax-01.vpn.internal");
        laxNode.setPublicIp("198.51.100.41");
        laxNode.setRegion("us-lax");
        laxNode.setStatus("ONLINE");
        laxNode.setPool("paid");
        laxNode.setAvailableToPaid(true);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(23L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(23L)).thenReturn(List.of(device));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(amsNode, laxNode));
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
        amsNode.setAvailableToPaid(true);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(24L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(24L)).thenReturn(List.of(device));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(amsNode));
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
        amsLow.setAvailableToPaid(true);
        amsLow.setCpuPercent(new java.math.BigDecimal("20.0"));
        amsLow.setActiveConnections(10);

        Node amsHigh = new Node();
        amsHigh.setId(7L);
        amsHigh.setRegion("nl-ams");
        amsHigh.setStatus("ONLINE");
        amsHigh.setPool("paid");
        amsHigh.setAvailableToPaid(true);
        amsHigh.setCpuPercent(new java.math.BigDecimal("30.0"));
        amsHigh.setActiveConnections(30);

        Node laxBusy = new Node();
        laxBusy.setId(8L);
        laxBusy.setRegion("us-lax");
        laxBusy.setStatus("ONLINE");
        laxBusy.setPool("paid");
        laxBusy.setAvailableToPaid(true);
        laxBusy.setCpuPercent(new java.math.BigDecimal("90.0"));
        laxBusy.setActiveConnections(200);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(25L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(amsLow, amsHigh, laxBusy));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(amsLow, amsHigh, laxBusy));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(25L);

        assertEquals(2, regions.size());
        SubscriptionExportService.RegionSummary ams = regions.stream()
                .filter(r -> r.region().equals("nl-ams")).findFirst().orElseThrow();
        SubscriptionExportService.RegionSummary lax = regions.stream()
                .filter(r -> r.region().equals("us-lax")).findFirst().orElseThrow();

        assertEquals(2, ams.nodeCount());
        assertEquals(25.0, ams.avgCpuPercent());
        assertNull(ams.avgBytesPerSec());
        assertNull(ams.avgMemoryPercent());
        // Neither node reports throughput/memory, so loadLevelFor's weighting
        // renormalizes onto CPU alone — same result as the pre-blend formula.
        assertEquals("LOW", ams.loadLevel());
        // Both regions are in the "pro" tariff's own pool ("paid"), so both
        // should read as accessible to this caller.
        assertTrue(ams.accessible());

        assertEquals(1, lax.nodeCount());
        assertEquals(90.0, lax.avgCpuPercent());
        assertEquals("HIGH", lax.loadLevel());
        assertTrue(lax.accessible());
    }

    @Test
    void testGetAvailableRegionsMarksOtherPoolRegionsInaccessibleButStillLists() {
        // The reported bug: a trial user's region picker only ever showed paid-pool
        // regions (hardcoded), so their own reachable trial region was invisible,
        // while an unreachable paid region looked pickable — picking it then
        // silently fell back to the real (trial) node with a vague "unavailable"
        // message. getAvailableRegions must now list every online region regardless
        // of pool, and flag which ones the caller's own tariff can actually reach.
        User user = new User();
        user.setId(33L);

        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setServerPool("trial");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(trial);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node trialNode = new Node();
        trialNode.setId(13L);
        trialNode.setRegion("in-mumbai");
        trialNode.setStatus("ONLINE");
        trialNode.setPool("trial");
        trialNode.setAvailableToTrial(true);
        trialNode.setAvailableToPaid(true);
        trialNode.setActiveConnections(1);

        Node paidNode = new Node();
        paidNode.setId(14L);
        paidNode.setRegion("fi-hel");
        paidNode.setStatus("ONLINE");
        paidNode.setPool("paid");
        paidNode.setAvailableToPaid(true);
        paidNode.setActiveConnections(0);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(33L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToTrialTrueAndStatus("ONLINE")).thenReturn(List.of(trialNode));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(trialNode, paidNode));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(33L);

        assertEquals(2, regions.size());
        SubscriptionExportService.RegionSummary mumbai = regions.stream()
                .filter(r -> r.region().equals("in-mumbai")).findFirst().orElseThrow();
        SubscriptionExportService.RegionSummary helsinki = regions.stream()
                .filter(r -> r.region().equals("fi-hel")).findFirst().orElseThrow();

        assertTrue(mumbai.accessible());
        assertFalse(helsinki.accessible());
    }

    @Test
    void testGetAvailableRegionsEverythingAccessibleWhenOwnPoolHasNoCapacity() {
        // Mirrors exportVlessLinks' real fallback: if the caller's own pool has
        // zero ONLINE nodes anywhere, exportVlessLinks transparently serves any
        // ONLINE node — so every region must read as accessible in that edge case
        // too, not just the caller's usual pool.
        User user = new User();
        user.setId(34L);

        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setServerPool("trial");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(trial);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node paidNode = new Node();
        paidNode.setId(15L);
        paidNode.setRegion("fi-hel");
        paidNode.setStatus("ONLINE");
        paidNode.setPool("paid");
        paidNode.setAvailableToPaid(true);
        paidNode.setActiveConnections(0);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(34L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToTrialTrueAndStatus("ONLINE")).thenReturn(List.of());
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(paidNode));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(34L);

        assertEquals(1, regions.size());
        assertTrue(regions.get(0).accessible());
    }

    @Test
    void testGetAvailableRegionsPaidUserSeesTrialRegionAsAccessibleToo() {
        // Pools aren't equal-standing silos: "trial" is a lesser/throttled bucket
        // a paying user gets as bonus/fallback capacity in addition to "paid" —
        // paid ⊇ trial. A paid user must never see their own trial-pool region
        // locked behind "requires a paid plan" (reported bug: it read that way
        // right after switching a trial account to a paid tariff).
        User user = new User();
        user.setId(35L);

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setServerPool("paid");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node trialNode = new Node();
        trialNode.setId(16L);
        trialNode.setRegion("in-mumbai");
        trialNode.setStatus("ONLINE");
        trialNode.setPool("trial");
        trialNode.setAvailableToTrial(true);
        trialNode.setAvailableToPaid(true);
        trialNode.setActiveConnections(1);

        Node paidNode = new Node();
        paidNode.setId(17L);
        paidNode.setRegion("fi-hel");
        paidNode.setStatus("ONLINE");
        paidNode.setPool("paid");
        paidNode.setAvailableToPaid(true);
        paidNode.setActiveConnections(0);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(35L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE"))
                .thenReturn(List.of(trialNode, paidNode));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(trialNode, paidNode));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(35L);

        assertEquals(2, regions.size());
        assertTrue(regions.stream().allMatch(SubscriptionExportService.RegionSummary::accessible));
    }

    @Test
    void testExportForOwnAppPaidUserGetsLinksForBothPaidAndTrialPoolNodes() {
        User user = new User();
        user.setId(36L);

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setServerPool("paid");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Device device = new Device();
        device.setId(101L);
        device.setUser(user);
        device.setIsActive(true);

        Node paidNode = new Node();
        paidNode.setId(18L);
        paidNode.setHostname("ams-01.vpn.internal");
        paidNode.setPublicIp("198.51.100.30");
        paidNode.setRegion("nl-ams");
        paidNode.setRealityPublicKey("k1");
        paidNode.setRealityShortIds(new String[]{"abcdef0123456789"});
        paidNode.setStatus("ONLINE");
        paidNode.setPool("paid");
        paidNode.setAvailableToPaid(true);

        Node trialNode = new Node();
        trialNode.setId(19L);
        trialNode.setHostname("mum-01.vpn.internal");
        trialNode.setPublicIp("198.51.100.31");
        trialNode.setRegion("in-mumbai");
        trialNode.setRealityPublicKey("k2");
        trialNode.setRealityShortIds(new String[]{"abcdef0123456789"});
        trialNode.setStatus("ONLINE");
        trialNode.setPool("trial");
        trialNode.setAvailableToTrial(true);
        trialNode.setAvailableToPaid(true);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(36L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(36L)).thenReturn(List.of(device));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE"))
                .thenReturn(List.of(paidNode, trialNode));
        when(deviceNodeKeyRepository.findByDeviceIdAndNodeId(eq(101L), any()))
                .thenAnswer(inv -> Optional.of(new DeviceNodeKey(device,
                        inv.getArgument(1).equals(18L) ? paidNode : trialNode, UUID.randomUUID())));

        List<String> links = exportService.exportVlessLinksForOwnApp(36L);

        assertEquals(2, links.size());
    }

    @Test
    void testGetAvailableRegionsWithZeroConnectionsNeverReportsHigh() {
        // Regression for the "idle node shown as loaded" bug: a node with zero
        // active connections has nothing to do with VPN traffic, but stale/
        // unrelated host CPU and memory (OS housekeeping, monitoring agents, a
        // residual reading right after some unrelated burst) could still read
        // fairly high, and it never reported a throughput figure at all (no
        // traffic-stats report is ever sent while idle — see
        // AgentGrpcClient#sendTrafficStats). Zero connections plus no
        // throughput data should cap the result at MEDIUM, never HIGH, even
        // with high CPU *and* high memory both present.
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
        idleButCpuHigh.setAvailableToPaid(true);
        idleButCpuHigh.setCpuPercent(new java.math.BigDecimal("90.0"));
        idleButCpuHigh.setMemoryUsedBytes(950L);
        idleButCpuHigh.setMemoryTotalBytes(1000L);
        idleButCpuHigh.setActiveConnections(0);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(27L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(idleButCpuHigh));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(idleButCpuHigh));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(27L);

        SubscriptionExportService.RegionSummary fra = regions.stream()
                .filter(r -> r.region().equals("eu-fra")).findFirst().orElseThrow();
        assertEquals(0L, fra.avgActiveConnections());
        assertNull(fra.avgBytesPerSec());
        assertEquals(95.0, fra.avgMemoryPercent());
        assertEquals("MEDIUM", fra.loadLevel());
    }

    @Test
    void testGetAvailableRegionsZeroConnectionsWithExplicitZeroThroughputAlsoCapsAtMedium() {
        // Same anchoring case as above, but for a node that HAS reported
        // throughput before and had it force-zeroed by processHeartbeat once
        // connections dropped (NodeManagementService#processHeartbeat), rather
        // than one that simply never reported a rate at all. Both shapes of
        // "no real traffic" must cap the same way.
        User user = new User();
        user.setId(29L);

        Tariff pro = new Tariff();
        pro.setId("pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node wentIdle = new Node();
        wentIdle.setId(10L);
        wentIdle.setRegion("ap-sgp");
        wentIdle.setStatus("ONLINE");
        wentIdle.setPool("paid");
        wentIdle.setAvailableToPaid(true);
        wentIdle.setCpuPercent(new java.math.BigDecimal("80.0"));
        wentIdle.setRecentBytesPerSec(0.0);
        wentIdle.setActiveConnections(0);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(29L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(wentIdle));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(wentIdle));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(29L);

        SubscriptionExportService.RegionSummary sgp = regions.stream()
                .filter(r -> r.region().equals("ap-sgp")).findFirst().orElseThrow();
        assertEquals(0.0, sgp.avgBytesPerSec());
        assertNotEquals("HIGH", sgp.loadLevel());
    }

    @Test
    void testGetAvailableRegionsHighThroughputWithModerateCpuReportsHigh() {
        // Throughput is the primary, most-honest load signal for a proxy
        // server (see loadLevelFor) — a node pushing a saturating amount of
        // traffic should read HIGH even though its CPU alone (50%) would only
        // have been MEDIUM.
        User user = new User();
        user.setId(31L);

        Tariff pro = new Tariff();
        pro.setId("pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node saturated = new Node();
        saturated.setId(11L);
        saturated.setRegion("eu-fra");
        saturated.setStatus("ONLINE");
        saturated.setPool("paid");
        saturated.setAvailableToPaid(true);
        saturated.setCpuPercent(new java.math.BigDecimal("50.0"));
        saturated.setActiveConnections(80);
        // 25,000,000 bytes/sec == 200 Mbps == loadLevelFor's throughput-score
        // reference point for "fully loaded".
        saturated.setRecentBytesPerSec(25_000_000.0);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(31L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(saturated));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(saturated));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(31L);

        SubscriptionExportService.RegionSummary fra = regions.stream()
                .filter(r -> r.region().equals("eu-fra")).findFirst().orElseThrow();
        assertEquals(25_000_000.0, fra.avgBytesPerSec());
        assertEquals("HIGH", fra.loadLevel());
    }

    @Test
    void testGetAvailableRegionsHighMemoryNudgesLowCpuRegionToMedium() {
        // Memory is the lightest of the three blended signals, but should
        // still be able to push a region that CPU alone would call LOW up
        // into MEDIUM — a node genuinely close to running out of memory is a
        // real "maybe avoid this one" signal even mid-CPU-idle.
        User user = new User();
        user.setId(32L);

        Tariff pro = new Tariff();
        pro.setId("pro");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node lowCpuHighMemory = new Node();
        lowCpuHighMemory.setId(12L);
        lowCpuHighMemory.setRegion("us-lax");
        lowCpuHighMemory.setStatus("ONLINE");
        lowCpuHighMemory.setPool("paid");
        lowCpuHighMemory.setAvailableToPaid(true);
        lowCpuHighMemory.setCpuPercent(new java.math.BigDecimal("30.0"));
        lowCpuHighMemory.setActiveConnections(20);
        lowCpuHighMemory.setMemoryUsedBytes(950L);
        lowCpuHighMemory.setMemoryTotalBytes(1000L);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(32L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(lowCpuHighMemory));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(lowCpuHighMemory));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(32L);

        SubscriptionExportService.RegionSummary lax = regions.stream()
                .filter(r -> r.region().equals("us-lax")).findFirst().orElseThrow();
        assertEquals(95.0, lax.avgMemoryPercent());
        // CPU alone (30%) would have been LOW; blended with high memory it's MEDIUM.
        assertEquals("MEDIUM", lax.loadLevel());
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

    @Test
    void testExportVlessLinksBlockedUserThrowsException() {
        User user = new User();
        user.setId(30L);
        user.setStatus("BLOCKED");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(7, ChronoUnit.DAYS));

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(30L, "ACTIVE"))
                .thenReturn(Optional.of(sub));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                exportService.exportVlessLinksForOwnApp(30L));
        assertTrue(ex.getMessage().contains("Account is suspended or blocked"));
    }

    @Test
    void testGetAvailableRegionsHidesTheCallersOwnRelayDevice() {
        // Reported by the repo owner: turning P2P relay mode on made their own
        // laptop appear as a connection region in their own app.
        //
        // Somebody else's relay device is gone from this list too, but for a
        // different reason and by a different rule (see
        // testARegionWithOnlyAP2pNodeIsNotOfferedAtAll): no client can dial a
        // p2p node yet at all. This case is specifically about the *owner*,
        // which must keep holding once that changes.
        User owner = new User();
        owner.setId(60L);
        User somebodyElse = new User();
        somebodyElse.setId(61L);

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setServerPool("paid");

        Subscription sub = new Subscription();
        sub.setUser(owner);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node vps = new Node();
        vps.setId(70L);
        vps.setRegion("Finland, Helsinki");
        vps.setStatus("ONLINE");
        vps.setType("vps");
        vps.setAvailableToPaid(true);
        // A VPS node is owned by whoever's bootstrap token registered it —
        // usually the operator, i.e. possibly this very caller. It must never
        // be hidden from them.
        vps.setOwnerUser(owner);

        Node myLaptop = new Node();
        myLaptop.setId(71L);
        myLaptop.setRegion("Spain, Madrid");
        myLaptop.setStatus("ONLINE");
        myLaptop.setType("p2p");
        myLaptop.setRelayMode("ALWAYS");
        myLaptop.setAvailableToPaid(true);
        myLaptop.setOwnerUser(owner);

        Node otherPersonsPhone = new Node();
        otherPersonsPhone.setId(72L);
        otherPersonsPhone.setRegion("Germany, Berlin");
        otherPersonsPhone.setStatus("ONLINE");
        otherPersonsPhone.setType("p2p");
        otherPersonsPhone.setRelayMode("ALWAYS");
        otherPersonsPhone.setAvailableToPaid(true);
        otherPersonsPhone.setOwnerUser(somebodyElse);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(60L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE"))
                .thenReturn(List.of(vps, myLaptop, otherPersonsPhone));
        when(nodeRepository.findByStatus("ONLINE"))
                .thenReturn(List.of(vps, myLaptop, otherPersonsPhone));

        List<String> regions = exportService.getAvailableRegions(60L).stream()
                .map(SubscriptionExportService.RegionSummary::region)
                .toList();

        assertEquals(List.of("Finland, Helsinki"), regions);
    }

    @Test
    void testExportNeverLinksTheCallersOwnRelayDevice() {
        // Belt and braces for the same rule on the path that actually hands
        // out credentials: even if a p2p node ever becomes directly dialable,
        // the caller's own device must not be among the links.
        User owner = new User();
        owner.setId(62L);

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setServerPool("paid");

        Subscription sub = new Subscription();
        sub.setUser(owner);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Device device = new Device();
        device.setId(500L);
        device.setUser(owner);
        device.setDeviceName("Phone");

        Node myLaptop = new Node();
        myLaptop.setId(73L);
        myLaptop.setHostname("MacBookPro");
        myLaptop.setPublicIp("10.0.0.5");
        myLaptop.setRegion("Spain, Madrid");
        myLaptop.setStatus("ONLINE");
        myLaptop.setType("p2p");
        myLaptop.setRelayMode("ALWAYS");
        myLaptop.setAvailableToPaid(true);
        myLaptop.setOwnerUser(owner);
        myLaptop.setRealityPublicKey("pk");

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(62L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.findByUserIdAndIsActiveTrue(62L)).thenReturn(List.of(device));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(myLaptop));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(myLaptop));

        assertTrue(exportService.exportVlessLinksForOwnApp(62L).isEmpty());
    }

    @Test
    void testARegionWithOnlyAP2pNodeIsNotOfferedAtAll() {
        // Reported live: a Pro subscriber saw "Montenegro, Podgorica — requires
        // a paid plan" with a padlock. The tariff had nothing to do with it —
        // `accessible` required a non-p2p node, so a p2p-only region came back
        // locked on every plan, and the clients label every locked row that
        // way. No client can connect through a p2p node yet at all, so the row
        // must not be offered rather than be permanently locked.
        User owner = new User();
        owner.setId(70L);
        User somebodyElse = new User();
        somebodyElse.setId(71L);

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setServerPool("paid");

        Subscription sub = new Subscription();
        sub.setUser(owner);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node vps = new Node();
        vps.setId(80L);
        vps.setRegion("Finland, Helsinki");
        vps.setStatus("ONLINE");
        vps.setType("vps");
        vps.setAvailableToPaid(true);

        // Somebody else's relay device, with both pools enabled — still not
        // something a client can dial today.
        Node relayDevice = new Node();
        relayDevice.setId(81L);
        relayDevice.setRegion("Montenegro, Podgorica");
        relayDevice.setStatus("ONLINE");
        relayDevice.setType("p2p");
        relayDevice.setRelayMode("ALWAYS");
        relayDevice.setAvailableToPaid(true);
        relayDevice.setAvailableToTrial(true);
        relayDevice.setOwnerUser(somebodyElse);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(70L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(vps, relayDevice));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(vps, relayDevice));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(70L);

        assertEquals(List.of("Finland, Helsinki"),
                regions.stream().map(SubscriptionExportService.RegionSummary::region).toList());
        assertTrue(regions.get(0).accessible());
    }

    @Test
    void testARegionIsCountedAndJudgedOnlyOnItsConnectableNodes() {
        // A relay device sitting in the same region as a real node must not
        // inflate that region's node count or its load average either: both
        // exist to help the user pick where to connect.
        User user = new User();
        user.setId(72L);

        Tariff pro = new Tariff();
        pro.setId("pro");
        pro.setServerPool("paid");

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(pro);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        Node vps = new Node();
        vps.setId(82L);
        vps.setRegion("Finland, Helsinki");
        vps.setStatus("ONLINE");
        vps.setType("vps");
        vps.setAvailableToPaid(true);
        vps.setActiveConnections(1);

        Node relayInSameRegion = new Node();
        relayInSameRegion.setId(83L);
        relayInSameRegion.setRegion("Finland, Helsinki");
        relayInSameRegion.setStatus("ONLINE");
        relayInSameRegion.setType("p2p");
        relayInSameRegion.setRelayMode("ALWAYS");
        relayInSameRegion.setAvailableToPaid(true);
        relayInSameRegion.setActiveConnections(999);

        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(72L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE")).thenReturn(List.of(vps, relayInSameRegion));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of(vps, relayInSameRegion));

        List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(72L);

        assertEquals(1, regions.size());
        assertEquals(1, regions.get(0).nodeCount(), "only the node a client can actually use counts");
        assertEquals(1, regions.get(0).avgActiveConnections(), "the relay's load must not skew the region's");
    }
}
