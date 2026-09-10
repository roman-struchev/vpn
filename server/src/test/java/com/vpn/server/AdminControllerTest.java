package com.vpn.server;

import com.vpn.server.controller.AdminController;
import com.vpn.server.entity.*;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.grpc.agent.v1.CommandType;
import com.vpn.server.grpc.agent.v1.ServerCommand;
import com.vpn.server.repository.*;
import com.vpn.server.service.BlockchainPaymentService;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.task.QuotaEnforcementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private NodeManagementService nodeManagementService;

    @Mock
    private NodeRepository nodeRepository;

    @Mock
    private AgentStreamServiceImpl agentStreamService;

    @Mock
    private QuotaEnforcementTask quotaEnforcementTask;

    @Mock
    private BlockchainPaymentService blockchainPaymentService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private BalanceEntryRepository balanceEntryRepository;

    @Mock
    private TransportPolicyRepository transportPolicyRepository;

    @Mock
    private ConnTelemetryRepository connTelemetryRepository;

    private AdminController adminController;

    @BeforeEach
    void setUp() {
        adminController = new AdminController(
                nodeManagementService,
                nodeRepository,
                agentStreamService,
                quotaEnforcementTask,
                blockchainPaymentService,
                userRepository,
                subscriptionRepository,
                balanceEntryRepository,
                transportPolicyRepository,
                connTelemetryRepository
        );
    }

    @Test
    void testGetDashboardMetrics() {
        when(userRepository.count()).thenReturn(150L);
        when(subscriptionRepository.countByStatus("ACTIVE")).thenReturn(120L);
        when(userRepository.sumBalanceUsdtMicro()).thenReturn(450_000_000L);
        when(subscriptionRepository.sumTrafficUsedBytes()).thenReturn(10_737_418_240L);
        when(nodeRepository.countByStatus("ONLINE")).thenReturn(5L);
        when(nodeRepository.count()).thenReturn(6L);
        when(connTelemetryRepository.aggregateByOperatorAndRegion(any(Instant.class))).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = adminController.getDashboardMetrics();
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(150L, body.get("totalUsers"));
        assertEquals(120L, body.get("activeSubscriptions"));
        assertEquals(450.0, body.get("totalBalanceUsdt"));
        assertEquals(5L, body.get("onlineNodes"));
    }

    @Test
    void testListUsers() {
        User user = new User();
        user.setId(10L);
        user.setEmail("user10@vpn.test");
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setBalanceUsdtMicro(5_000_000L);

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(10L, "ACTIVE"))
                .thenReturn(Optional.empty());

        ResponseEntity<List<Map<String, Object>>> response = adminController.listUsers();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("user10@vpn.test", response.getBody().get(0).get("email"));
    }

    @Test
    void testAdjustUserBalance() {
        User user = new User();
        user.setId(12L);
        user.setBalanceUsdtMicro(2_000_000L);

        when(userRepository.findById(12L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        Map<String, Object> req = Map.of("amountMicro", 3_000_000L, "description", "Admin credit");
        ResponseEntity<?> response = adminController.adjustUserBalance(12L, req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5_000_000L, user.getBalanceUsdtMicro());
        verify(balanceEntryRepository).save(any(BalanceEntry.class));
    }

    @Test
    void testUpdateUserStatusBlocked() {
        User user = new User();
        user.setId(15L);
        user.setStatus("ACTIVE");

        when(userRepository.findById(15L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        ResponseEntity<?> response = adminController.updateUserStatus(15L, Map.of("status", "BLOCKED"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("BLOCKED", user.getStatus());
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testUpdateNodePool() {
        Node node = new Node();
        node.setId(3L);
        node.setPool("paid");

        when(nodeRepository.findById(3L)).thenReturn(Optional.of(node));
        when(nodeRepository.save(any(Node.class))).thenReturn(node);

        ResponseEntity<?> response = adminController.updateNodePool(3L, "quarantine");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("quarantine", node.getPool());
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testSaveTransportPolicy() {
        TransportPolicy policy = new TransportPolicy();
        policy.setScope("region");
        policy.setScopeValue("RU-MOW");
        policy.setPrimaryTransport("XHTTP");

        when(transportPolicyRepository.save(any(TransportPolicy.class))).thenReturn(policy);

        ResponseEntity<?> response = adminController.saveTransportPolicy(policy);
        assertEquals(200, response.getStatusCode().value());
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testCreateBootstrapToken() {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_abc123");
        token.setAssignedPool("paid");
        token.setAssignedType("direct");
        token.setExpiresAt(Instant.now());

        when(nodeManagementService.createBootstrapToken(eq("paid"), eq("direct"), eq(24)))
                .thenReturn(token);

        ResponseEntity<?> response = adminController.createBootstrapToken("paid", "direct", 24);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("bt_abc123", body.get("token"));
    }

    @Test
    void testListNodes() {
        Node node = new Node();
        node.setId(1L);
        node.setHostname("node-1.vpn.internal");

        when(nodeRepository.findAll()).thenReturn(List.of(node));

        ResponseEntity<List<Node>> response = adminController.listNodes();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void testForceConfigSync() {
        ResponseEntity<?> response = adminController.forceConfigSync(1L);
        assertEquals(200, response.getStatusCode().value());
        verify(agentStreamService).pushConfigSync(1L);
    }

    @Test
    void testSendNodeCommandValid() {
        when(agentStreamService.sendCommand(eq(1L), any(ServerCommand.class))).thenReturn(true);

        ResponseEntity<?> response = adminController.sendNodeCommand(1L, "COMMAND_TYPE_RESTART_XRAY");
        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(true, body.get("success"));
        assertEquals("COMMAND_TYPE_RESTART_XRAY", body.get("command"));
    }

    @Test
    void testSendNodeCommandInvalid() {
        ResponseEntity<?> response = adminController.sendNodeCommand(1L, "INVALID_COMMAND");
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void testTriggerQuotaEnforcement() {
        ResponseEntity<?> response = adminController.triggerQuotaEnforcement();
        assertEquals(200, response.getStatusCode().value());
        verify(quotaEnforcementTask).runEnforcement();
    }

    @Test
    void testReconcileDepositMatched() {
        User user = new User();
        user.setId(7L);

        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setId(99L);
        invoice.setUser(user);
        invoice.setExpectedAmountUsdtMicro(1_000_000L);
        invoice.setActualAmountUsdtMicro(1_000_100L);

        when(blockchainPaymentService.getDefaultTronDepositAddress()).thenReturn("TDefault");
        when(blockchainPaymentService.processIncomingDeposit(eq("TRC20"), eq("TDefault"), eq(1_000_100L), anyString()))
                .thenReturn(invoice);

        Map<String, Object> req = Map.of("amountMicro", 1_000_100L);
        ResponseEntity<?> response = adminController.reconcileDeposit(req);
        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("MATCHED_AND_CREDITED", body.get("status"));
        assertEquals(99L, body.get("invoiceId"));
    }
}
