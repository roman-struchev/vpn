package com.vpn.server;

import com.vpn.server.controller.AdminController;
import com.vpn.server.entity.CryptoInvoice;
import com.vpn.server.entity.Node;
import com.vpn.server.entity.NodeBootstrapToken;
import com.vpn.server.entity.User;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.grpc.agent.v1.CommandType;
import com.vpn.server.grpc.agent.v1.ServerCommand;
import com.vpn.server.repository.NodeRepository;
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

    private AdminController adminController;

    @BeforeEach
    void setUp() {
        adminController = new AdminController(
                nodeManagementService,
                nodeRepository,
                agentStreamService,
                quotaEnforcementTask,
                blockchainPaymentService
        );
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
