package com.vpn.server;

import com.vpn.server.entity.*;
import com.vpn.server.grpc.agent.v1.*;
import com.vpn.server.repository.*;
import com.vpn.server.service.NodeManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

        when(tokenRepository.findByTokenAndIsUsedFalse("bt_valid_token")).thenReturn(Optional.of(token));
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
    void testRegisterNodeExpiredTokenThrows() {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_expired");
        token.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        when(tokenRepository.findByTokenAndIsUsedFalse("bt_expired")).thenReturn(Optional.of(token));

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken("bt_expired")
                .setHostname("node-01")
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                nodeManagementService.registerNode(request));
    }

    @Test
    void testAuthenticateNode() {
        Node node = new Node();
        node.setId(5L);

        NodeCredential cred = new NodeCredential();
        cred.setNode(node);
        cred.setTokenHash(passwordEncoder.encode("secret-raw-token"));

        when(credentialRepository.findByNodeIdAndRevokedAtIsNull(5L)).thenReturn(Optional.of(cred));

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
        node.setRealityPublicKey("realityPrivKeyBase64");
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
}
