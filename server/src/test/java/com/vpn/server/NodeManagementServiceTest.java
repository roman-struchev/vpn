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
}
