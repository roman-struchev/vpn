package com.vpn.server;

import com.vpn.server.entity.*;
import com.vpn.server.grpc.agent.v1.*;
import com.vpn.server.repository.*;
import com.vpn.server.service.NodeManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NodeManagementServiceTest {

    private NodeRepository nodeRepository;
    private NodeBootstrapTokenRepository tokenRepository;
    private NodeCredentialRepository credentialRepository;
    private DeviceNodeKeyRepository deviceNodeKeyRepository;
    private SubscriptionRepository subscriptionRepository;
    private ConnTelemetryRepository telemetryRepository;
    private PasswordEncoder passwordEncoder;
    private NodeManagementService nodeManagementService;

    @BeforeEach
    void setUp() {
        nodeRepository = mock(NodeRepository.class);
        tokenRepository = mock(NodeBootstrapTokenRepository.class);
        credentialRepository = mock(NodeCredentialRepository.class);
        deviceNodeKeyRepository = mock(DeviceNodeKeyRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        telemetryRepository = mock(ConnTelemetryRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();

        nodeManagementService = new NodeManagementService(
                nodeRepository,
                tokenRepository,
                credentialRepository,
                deviceNodeKeyRepository,
                subscriptionRepository,
                telemetryRepository,
                passwordEncoder
        );

        ReflectionTestUtils.setField(nodeManagementService, "defaultRealityDest", "dl.google.com:443");
        ReflectionTestUtils.setField(nodeManagementService, "defaultServerNames", "dl.google.com");
        ReflectionTestUtils.setField(nodeManagementService, "heartbeatIntervalSec", 30);
        ReflectionTestUtils.setField(nodeManagementService, "statsIntervalSec", 30);
        ReflectionTestUtils.setField(nodeManagementService, "grpcFallbackPort", 8443);
        ReflectionTestUtils.setField(nodeManagementService, "grpcFallbackServiceName", "vless-grpc");
        ReflectionTestUtils.setField(nodeManagementService, "cdnCertDir", "/etc/xray/certs");
    }

    @Test
    void testRegisterNodeSuccess() {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_valid_token");
        token.setAssignedPool("paid");
        token.setAssignedType("direct");
        token.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));

        when(tokenRepository.findByToken("bt_valid_token")).thenReturn(Optional.of(token));
        when(nodeRepository.findByHostname("node-nl-01")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> {
            Node n = i.getArgument(0);
            n.setId(10L);
            return n;
        });

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_valid_token")
                .setHostname("node-nl-01")
                .setPublicIp("198.51.100.1")
                .setAgentVersion("1.0.0")
                .setRegion("nl-ams")
                .build();

        RegisterNodeResponse resp = nodeManagementService.registerNode(request);

        assertNotNull(resp);
        assertEquals(10L, resp.getNodeId());
        assertNotNull(resp.getNodeToken());
        assertEquals(NodePool.NODE_POOL_PAID, resp.getAssignedPool());
        assertEquals(NodeType.NODE_TYPE_DIRECT, resp.getAssignedType());
        assertTrue(token.getIsUsed());
        verify(credentialRepository, times(1)).save(any(NodeCredential.class));
    }

    @Test
    void testRegisterNodeGeneratesRealityKeyPairAndShortIds() {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_valid_token_2");
        token.setAssignedPool("paid");
        token.setAssignedType("direct");
        token.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));

        when(tokenRepository.findByToken("bt_valid_token_2")).thenReturn(Optional.of(token));
        when(nodeRepository.findByHostname("node-nl-02")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> {
            Node n = i.getArgument(0);
            n.setId(11L);
            return n;
        });

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_valid_token_2")
                .setHostname("node-nl-02")
                .setPublicIp("198.51.100.2")
                .setAgentVersion("1.0.0")
                .setRegion("nl-ams")
                .build();

        nodeManagementService.registerNode(request);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(nodeCaptor.capture());
        Node saved = nodeCaptor.getValue();

        // No key generation code existed before this — new nodes always had a
        // NULL realityPublicKey, which broke xray's REALITY outbound/inbound
        // config on every client and node with "empty \"publicKey\"" /
        // "empty \"password\"" (nothing to ever populate these fields).
        assertNotNull(saved.getRealityPublicKey());
        assertNotNull(saved.getRealityPrivateKey());
        assertNotEquals(saved.getRealityPublicKey(), saved.getRealityPrivateKey());
        assertNotNull(saved.getRealityShortIds());
        assertEquals(2, saved.getRealityShortIds().length);
    }

    @Test
    void testRegisterNodeReusesUnexpiredTokenForASecondNode() {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_reusable");
        token.setAssignedPool("paid");
        token.setAssignedType("direct");
        token.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));

        when(tokenRepository.findByToken("bt_reusable")).thenReturn(Optional.of(token));
        when(nodeRepository.findByHostname(anyString())).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> {
            Node n = i.getArgument(0);
            n.setId(n.getHostname().equals("node-a") ? 20L : 21L);
            return n;
        });

        nodeManagementService.registerNode(RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_reusable")
                .setHostname("node-a")
                .setPublicIp("198.51.100.10")
                .setRegion("nl-ams")
                .build());
        nodeManagementService.registerNode(RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_reusable")
                .setHostname("node-b")
                .setPublicIp("198.51.100.11")
                .setRegion("nl-ams")
                .build());

        assertTrue(token.getIsUsed());
        assertEquals(2, token.getUseCount());
    }

    @Test
    void testRegisterNodeExpiredTokenThrows() {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_expired");
        token.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        when(tokenRepository.findByToken("bt_expired")).thenReturn(Optional.of(token));

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_expired")
                .setHostname("node-01")
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                nodeManagementService.registerNode(request));
    }

    @Test
    void testRegisterNodeP2pTypeGetsBothTariffAccessFlagsAndOwnerFromToken() {
        // docs/research/P2P_RELAY_FEASIBILITY.md §8.3/§8.4: a p2p node is
        // available to both trial and paid tariffs simultaneously by
        // convention — createP2pBootstrapTokenForUser mints its token with
        // assignedPool="both", and applyTariffAccessFlags is now purely
        // pool-driven (no more type=p2p special-casing) — and its owner
        // comes only from the bootstrap token that registered it, never
        // from a self-declared field in the request.
        User owner = new User();
        owner.setId(500L);

        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_p2p_1");
        token.setAssignedPool("both");
        token.setAssignedType("p2p");
        token.setOwnerUser(owner);
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));

        when(tokenRepository.findByToken("bt_p2p_1")).thenReturn(Optional.of(token));
        when(nodeRepository.findByHostname("laptop-p2p-1")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> {
            Node n = i.getArgument(0);
            n.setId(50L);
            return n;
        });

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_p2p_1")
                .setHostname("laptop-p2p-1")
                .setPublicIp("0.0.0.0")
                .setRegion("nl-ams")
                .setRelayMode("ALWAYS")
                .build();

        nodeManagementService.registerNode(request);

        ArgumentCaptor<Node> captor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(captor.capture());
        Node saved = captor.getValue();

        assertTrue(saved.getAvailableToTrial());
        assertTrue(saved.getAvailableToPaid());
        assertEquals(owner, saved.getOwnerUser());
        assertEquals("ALWAYS", saved.getRelayMode());
        assertTrue(saved.isEligibleForRelay());
    }

    @Test
    void testReRegisteringAnExistingNode_neverResetsAnAdminChangedPool() {
        // Regression: pool (and the tariff-access flags it derives) used to
        // get reset unconditionally on every registerNode call, including a
        // RE-registration of an already-existing node (same hostname) —
        // silently reverting an admin's later pool change (via
        // AdminController#updateNodePool) back to the bootstrap token's
        // original assignedPool the next time that node's agent restarted.
        // p2p relay clients re-register on every app restart and every
        // relay-mode OFF->ON toggle, so this would have manifested within
        // minutes for exactly the node type that churns the most.
        User owner = new User();
        owner.setId(500L);

        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_p2p_2");
        token.setAssignedPool("both"); // the token's own default — must NOT override the admin's later choice below
        token.setAssignedType("p2p");
        token.setOwnerUser(owner);
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));

        Node existing = new Node();
        existing.setId(60L);
        existing.setHostname("laptop-p2p-2");
        existing.setType("p2p");
        // An admin manually restricted this node to paid-only after it first
        // registered (e.g. via the pool dropdown) — this must survive a
        // re-registration, not get silently reset back to the token's own
        // "both" default.
        existing.setPool("paid");
        existing.setAvailableToTrial(false);
        existing.setAvailableToPaid(true);

        when(tokenRepository.findByToken("bt_p2p_2")).thenReturn(Optional.of(token));
        when(nodeRepository.findByHostname("laptop-p2p-2")).thenReturn(Optional.of(existing));
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> i.getArgument(0));

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_p2p_2")
                .setHostname("laptop-p2p-2")
                .setPublicIp("0.0.0.0")
                .setRegion("nl-ams")
                .setRelayMode("ALWAYS")
                .build();

        nodeManagementService.registerNode(request);

        ArgumentCaptor<Node> captor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(captor.capture());
        Node saved = captor.getValue();

        assertEquals("paid", saved.getPool(), "admin's pool change must survive re-registration");
        assertFalse(saved.getAvailableToTrial());
        assertTrue(saved.getAvailableToPaid());
    }

    @Test
    void testCreateP2pBootstrapTokenForUser_defaultsToBothPool() {
        User user = new User();
        user.setId(42L);
        when(tokenRepository.save(any(NodeBootstrapToken.class))).thenAnswer(i -> i.getArgument(0));

        NodeBootstrapToken token = nodeManagementService.createP2pBootstrapTokenForUser(user);

        assertEquals("both", token.getAssignedPool());
        assertEquals("p2p", token.getAssignedType());
        assertEquals(user, token.getOwnerUser());
    }

    @Test
    void testRegisterNodeBothPoolGetsBothTariffAccessFlags() {
        // "both" (docs §8.3) is how an admin makes a regular (non-p2p)
        // direct/cdn node dual-accessible without touching its type —
        // functionally identical to "trial" pool's derived flags, but a
        // distinct pool identity so it isn't confused with real trial-tier
        // capacity for other purposes (e.g. DynamicRoutingService's
        // quarantine/reserve handling, which only special-cases those two
        // pool values, not "trial" or "both").
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_both_1");
        token.setAssignedPool("both");
        token.setAssignedType("direct");
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));

        when(tokenRepository.findByToken("bt_both_1")).thenReturn(Optional.of(token));
        when(nodeRepository.findByHostname("vps-both-1")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> {
            Node n = i.getArgument(0);
            n.setId(70L);
            return n;
        });

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_both_1")
                .setHostname("vps-both-1")
                .setPublicIp("198.51.100.2")
                .setRegion("de-fra")
                .build();

        nodeManagementService.registerNode(request);

        ArgumentCaptor<Node> captor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(captor.capture());
        Node saved = captor.getValue();

        assertEquals("both", saved.getPool());
        assertTrue(saved.getAvailableToTrial());
        assertTrue(saved.getAvailableToPaid());
    }

    @Test
    void testRegisterNodeDirectPaidTypeGetsOnlyPaidFlagAndNoOwner() {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_direct_1");
        token.setAssignedPool("paid");
        token.setAssignedType("direct");
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));

        when(tokenRepository.findByToken("bt_direct_1")).thenReturn(Optional.of(token));
        when(nodeRepository.findByHostname("vps-01")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> {
            Node n = i.getArgument(0);
            n.setId(51L);
            return n;
        });

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_direct_1")
                .setHostname("vps-01")
                .setPublicIp("198.51.100.1")
                .setRegion("nl-ams")
                .build();

        nodeManagementService.registerNode(request);

        ArgumentCaptor<Node> captor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(captor.capture());
        Node saved = captor.getValue();

        assertFalse(saved.getAvailableToTrial());
        assertTrue(saved.getAvailableToPaid());
        assertNull(saved.getOwnerUser());
        // Registration never touched relay_mode (only meaningful for p2p) —
        // stays at the entity's own default.
        assertEquals("OFF", saved.getRelayMode());
    }

    @Test
    void testRegisterNodeTrialTypeGetsBothFlagsTrue() {
        // Paid tariffs already reach trial-pool nodes as bonus/fallback
        // capacity (this session's earlier paid-⊇-trial fix) — a
        // freshly-registered trial-pool node must reproduce that, not just
        // pre-existing backfilled rows.
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_trial_1");
        token.setAssignedPool("trial");
        token.setAssignedType("direct");
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));

        when(tokenRepository.findByToken("bt_trial_1")).thenReturn(Optional.of(token));
        when(nodeRepository.findByHostname("vps-trial-01")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> {
            Node n = i.getArgument(0);
            n.setId(52L);
            return n;
        });

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_trial_1")
                .setHostname("vps-trial-01")
                .setPublicIp("198.51.100.2")
                .setRegion("in-mumbai")
                .build();

        nodeManagementService.registerNode(request);

        ArgumentCaptor<Node> captor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(captor.capture());
        Node saved = captor.getValue();

        assertTrue(saved.getAvailableToTrial());
        assertTrue(saved.getAvailableToPaid());
    }

    @Test
    void testProcessHeartbeatUpdatesRelayWindow() {
        Node node = new Node();
        node.setId(60L);
        node.setType("p2p");
        node.setRelayMode("OFF");
        when(nodeRepository.findById(60L)).thenReturn(Optional.of(node));
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> i.getArgument(0));

        long expiresAtMs = Instant.now().plus(8, ChronoUnit.HOURS).toEpochMilli();
        Heartbeat heartbeat = Heartbeat.newBuilder()
                .setCpuPercent(10.0)
                .setActiveConnections(1)
                .setRelayMode("TIMED")
                .setRelayExpiresAtEpochMs(expiresAtMs)
                .build();

        nodeManagementService.processHeartbeat(60L, heartbeat);

        assertEquals("TIMED", node.getRelayMode());
        assertEquals(expiresAtMs, node.getRelayExpiresAt().toEpochMilli());
    }

    @Test
    void testProcessHeartbeatWithBlankRelayModeLeavesExistingWindowUnchanged() {
        // A plain heartbeat that doesn't touch relay state at all (relay_mode
        // left at its proto default, "") must not silently reset an ALWAYS/
        // TIMED node back to OFF.
        Node node = new Node();
        node.setId(61L);
        node.setType("p2p");
        node.setRelayMode("ALWAYS");
        when(nodeRepository.findById(61L)).thenReturn(Optional.of(node));
        when(nodeRepository.save(any(Node.class))).thenAnswer(i -> i.getArgument(0));

        Heartbeat heartbeat = Heartbeat.newBuilder()
                .setCpuPercent(5.0)
                .setActiveConnections(0)
                .build();

        nodeManagementService.processHeartbeat(61L, heartbeat);

        assertEquals("ALWAYS", node.getRelayMode());
    }

    @Test
    void testAuthenticateNode() {
        Node node = new Node();
        node.setId(5L);

        NodeCredential cred = new NodeCredential();
        cred.setNode(node);
        cred.setTokenHash(passwordEncoder.encode("secret-raw-token"));

        when(credentialRepository.findAllByNodeIdAndRevokedAtIsNull(5L)).thenReturn(List.of(cred));

        assertTrue(nodeManagementService.authenticateNode(5L, "secret-raw-token"));
        assertFalse(nodeManagementService.authenticateNode(5L, "wrong-token"));
        assertFalse(nodeManagementService.authenticateNode(999L, "secret-raw-token"));
    }

    @Test
    void testBuildNodeConfigSyncDirectNodeIncludesGrpcRealityFallback() {
        Node node = new Node();
        node.setId(7L);
        node.setHostname("node-direct-01");
        node.setType("direct");
        node.setConfigVersion(1L);
        node.setRealityPublicKey("realityPubKeyBase64");
        node.setRealityPrivateKey("realityPrivKeyBase64");
        node.setRealityShortIds(new String[]{"0123456789abcdef"});

        when(nodeRepository.findById(7L)).thenReturn(Optional.of(node));
        when(deviceNodeKeyRepository.findActiveKeysByNodeId(7L)).thenReturn(List.of());

        ConfigSync sync = nodeManagementService.buildNodeConfigSync(7L);

        assertEquals("xhttp", sync.getInbound().getTransport());
        assertTrue(sync.getInbound().getReality().getEnabled());
        assertFalse(sync.getInbound().hasTlsSettings() && sync.getInbound().getTlsSettings().getEnabled());

        assertTrue(sync.hasFallbackInbound());
        InboundConfig fallback = sync.getFallbackInbound();
        assertEquals("grpc", fallback.getTransport());
        assertEquals(8443, fallback.getListenPort());
        assertTrue(fallback.getReality().getEnabled());
        // Same Reality key material on both transports — no new keys needed to switch.
        assertEquals("realityPrivKeyBase64", fallback.getReality().getPrivateKey());
        assertEquals("vless-grpc", fallback.getGrpcSettings().getServiceName());
    }

    @Test
    void testBuildNodeConfigSyncCdnNodeUsesRealTlsNotReality() {
        Node node = new Node();
        node.setId(8L);
        node.setHostname("edge.example.com");
        node.setType("cdn");
        node.setConfigVersion(1L);

        when(nodeRepository.findById(8L)).thenReturn(Optional.of(node));
        when(deviceNodeKeyRepository.findActiveKeysByNodeId(8L)).thenReturn(List.of());

        ConfigSync sync = nodeManagementService.buildNodeConfigSync(8L);

        assertFalse(sync.getInbound().getReality().getEnabled());
        assertTrue(sync.getInbound().getTlsSettings().getEnabled());
        assertEquals("edge.example.com", sync.getInbound().getTlsSettings().getServerName());
        assertEquals("/etc/xray/certs/edge.example.com/fullchain.pem", sync.getInbound().getTlsSettings().getCertPath());
        // CDN nodes don't get a gRPC fallback — the CDN's own framing already covers that role.
        assertFalse(sync.hasFallbackInbound());
    }

    @Test
    void testBuildNodeConfigSyncExcludesBlockedUserEvenWithUnexpiredSubscription() {
        Node node = new Node();
        node.setId(9L);
        node.setHostname("node-09");
        node.setType("direct");
        node.setConfigVersion(1L);

        User blockedUser = new User();
        blockedUser.setId(42L);
        blockedUser.setStatus("BLOCKED");

        Device device = new Device();
        device.setId(100L);
        device.setUser(blockedUser);
        device.setIsActive(true);

        UUID uuid = UUID.randomUUID();
        DeviceNodeKey key = new DeviceNodeKey(device, node, uuid);

        Subscription sub = new Subscription();
        sub.setUser(blockedUser);
        sub.setStatus("ACTIVE");
        sub.setTrafficUsedBytes(0L);
        sub.setTrafficLimitBytes(100_000_000_000L);
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));

        when(nodeRepository.findById(9L)).thenReturn(Optional.of(node));
        when(deviceNodeKeyRepository.findActiveKeysByNodeId(9L)).thenReturn(List.of(key));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(42L, "ACTIVE"))
                .thenReturn(Optional.of(sub));

        ConfigSync sync = nodeManagementService.buildNodeConfigSync(9L);

        assertEquals(1, sync.getClientsCount());
        assertFalse(sync.getClients(0).getIsActive(),
                "a BLOCKED user must lose VPN access even with an unexpired subscription");
    }

    @Test
    void testProcessTrafficStatsComputesRecentBytesPerSec() {
        // Regression for the "region load ignores actual throughput" gap: bytes
        // reported this round, divided by elapsed time since the previous
        // report, should give a bytes/sec figure the region picker can use
        // (SubscriptionExportService#loadLevelFor) — a far more honest "is this
        // node busy" signal for a proxy than CPU alone.
        Node node = new Node();
        node.setId(30L);
        node.setTotalBytesServed(1_000L);
        // A wide, fixed window (100s) keeps the expected rate stable regardless
        // of how long this test itself takes to run.
        Instant previousReportAt = Instant.now().minusSeconds(100);
        node.setLastTrafficStatsAt(previousReportAt);

        when(nodeRepository.findById(30L)).thenReturn(Optional.of(node));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(anyLong(), eq("ACTIVE")))
                .thenReturn(Optional.empty());

        TrafficStatsReport report = TrafficStatsReport.newBuilder()
                .addDeltas(ClientTrafficDelta.newBuilder()
                        .setUserId(1L)
                        .setDeviceId(1L)
                        .setUplinkBytes(300_000L)
                        .setDownlinkBytes(200_000L)
                        .build())
                .build();

        nodeManagementService.processTrafficStats(30L, report);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(nodeCaptor.capture());
        Node saved = nodeCaptor.getValue();

        assertEquals(501_000L, saved.getTotalBytesServed());
        assertNotNull(saved.getRecentBytesPerSec());
        // ~500000 bytes / ~100s == ~5000 bytes/sec; generous tolerance for the
        // few ms of real wall-clock time this test itself takes.
        assertEquals(5000.0, saved.getRecentBytesPerSec(), 50.0);
        assertTrue(saved.getLastTrafficStatsAt().isAfter(previousReportAt));
    }

    @Test
    void testProcessTrafficStatsFirstReportSkipsRateButRecordsTimestamp() {
        // No prior timestamp to diff against yet — must not crash or invent a
        // rate; recentBytesPerSec should simply stay whatever it was (null on a
        // brand-new node) until the next report can compute a real one.
        Node node = new Node();
        node.setId(31L);
        node.setTotalBytesServed(0L);
        node.setLastTrafficStatsAt(null);

        when(nodeRepository.findById(31L)).thenReturn(Optional.of(node));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(anyLong(), eq("ACTIVE")))
                .thenReturn(Optional.empty());

        TrafficStatsReport report = TrafficStatsReport.newBuilder()
                .addDeltas(ClientTrafficDelta.newBuilder()
                        .setUserId(2L)
                        .setDeviceId(2L)
                        .setUplinkBytes(1_000L)
                        .setDownlinkBytes(1_000L)
                        .build())
                .build();

        nodeManagementService.processTrafficStats(31L, report);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(nodeCaptor.capture());
        Node saved = nodeCaptor.getValue();

        assertEquals(2_000L, saved.getTotalBytesServed());
        assertNull(saved.getRecentBytesPerSec());
        assertNotNull(saved.getLastTrafficStatsAt());
    }

    @Test
    void testProcessHeartbeatZeroesRecentBytesPerSecWhenNoActiveConnections() {
        // Regression: the agent only ever sends a traffic-stats report while it
        // has connected users (AgentGrpcClient#sendTrafficStats), so once
        // connections drop to zero, recentBytesPerSec would otherwise keep
        // showing stale throughput forever — the same "smeared stale history"
        // failure mode the CPU load-average bug had, just for throughput.
        Node node = new Node();
        node.setId(32L);
        node.setRecentBytesPerSec(12_345.0);

        when(nodeRepository.findById(32L)).thenReturn(Optional.of(node));

        Heartbeat heartbeat = Heartbeat.newBuilder()
                .setCpuPercent(5.0)
                .setCpuCount(4)
                .setMemoryUsedBytes(100L)
                .setMemoryTotalBytes(200L)
                .setActiveConnections(0)
                .build();

        nodeManagementService.processHeartbeat(32L, heartbeat);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(nodeCaptor.capture());
        Node saved = nodeCaptor.getValue();

        assertEquals(0.0, saved.getRecentBytesPerSec());
    }

    @Test
    void testProcessHeartbeatPreservesRecentBytesPerSecWhenConnectionsPresent() {
        Node node = new Node();
        node.setId(33L);
        node.setRecentBytesPerSec(12_345.0);

        when(nodeRepository.findById(33L)).thenReturn(Optional.of(node));

        Heartbeat heartbeat = Heartbeat.newBuilder()
                .setCpuPercent(5.0)
                .setCpuCount(4)
                .setMemoryUsedBytes(100L)
                .setMemoryTotalBytes(200L)
                .setActiveConnections(3)
                .build();

        nodeManagementService.processHeartbeat(33L, heartbeat);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        verify(nodeRepository, atLeastOnce()).save(nodeCaptor.capture());
        Node saved = nodeCaptor.getValue();

        assertEquals(12_345.0, saved.getRecentBytesPerSec());
    }
}
