package com.vpn.server;

import com.vpn.server.controller.P2pRelayController;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.P2pRelayCreditRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.service.P2pRelayAccountingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "You cannot relay through your own device" — enforced on the server, not
 * left to the client, for the reasons in Node#isOwnRelayDeviceOf (a self-relay
 * hides nothing from a censor and would credit the account for carrying its
 * own bytes).
 */
@ExtendWith(MockitoExtension.class)
class P2pRelayControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private NodeManagementService nodeManagementService;
    @Mock private AgentStreamServiceImpl agentStreamService;
    @Mock private P2pRelayAccountingService p2pRelayAccountingService;
    @Mock private P2pRelayCreditRepository creditRepository;
    @Mock private Authentication auth;

    private P2pRelayController controller;

    private static final long CALLER_ID = 7L;
    private static final long OWN_NODE_ID = 100L;
    private static final long SOMEBODY_ELSES_NODE_ID = 200L;

    @BeforeEach
    void setUp() {
        controller = new P2pRelayController(
                userRepository, nodeManagementService, agentStreamService,
                p2pRelayAccountingService, creditRepository);
        lenient().when(auth.getPrincipal()).thenReturn(CALLER_ID);
        lenient().when(nodeManagementService.isOwnRelayDevice(OWN_NODE_ID, CALLER_ID)).thenReturn(true);
        lenient().when(nodeManagementService.isOwnRelayDevice(SOMEBODY_ELSES_NODE_ID, CALLER_ID)).thenReturn(false);
    }

    private static Map<String, String> signalBody() {
        return Map.of("sessionId", "s-1", "payloadBase64", Base64.getEncoder().encodeToString("offer".getBytes()));
    }

    @Test
    void testSignalToOwnRelayDeviceIsRefusedAndNeverReachesTheNode() {
        ResponseEntity<?> response = controller.sendSignal(OWN_NODE_ID, signalBody(), auth);

        assertEquals(403, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("your own device"), response.getBody().toString());
        verify(agentStreamService, never()).sendSignalToNodeAndAwaitReply(anyLong(), anyString(), any());
    }

    @Test
    void testSignalToSomebodyElsesRelayNodeStillGoesThrough() {
        when(agentStreamService.sendSignalToNodeAndAwaitReply(eq(SOMEBODY_ELSES_NODE_ID), eq("s-1"), any()))
                .thenReturn("answer".getBytes());

        ResponseEntity<?> response = controller.sendSignal(SOMEBODY_ELSES_NODE_ID, signalBody(), auth);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testClientTrafficReportForOwnRelayDeviceIsRefusedAndNotAccounted() {
        // The self-crediting half: pairing this with the relay agent's own
        // report is what would mint credit, so it must not be recorded at all.
        ResponseEntity<?> response = controller.reportSessionTraffic(
                "s-2", Map.of("nodeId", OWN_NODE_ID, "bytesRelayed", 1024), auth);

        assertEquals(403, response.getStatusCode().value());
        verify(p2pRelayAccountingService, never()).recordClientReport(anyLong(), anyString(), anyLong());
    }

    @Test
    void testClientTrafficReportForSomebodyElsesNodeIsAccounted() {
        ResponseEntity<?> response = controller.reportSessionTraffic(
                "s-3", Map.of("nodeId", SOMEBODY_ELSES_NODE_ID, "bytesRelayed", 2048), auth);

        assertEquals(200, response.getStatusCode().value());
        verify(p2pRelayAccountingService).recordClientReport(SOMEBODY_ELSES_NODE_ID, "s-3", 2048L);
    }
}
