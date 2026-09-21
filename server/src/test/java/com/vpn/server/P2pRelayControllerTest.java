package com.vpn.server;

import com.vpn.server.controller.P2pRelayController;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.P2pRelayCreditRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.service.P2pRelayAccountingService;
import com.vpn.server.service.P2pRelayDirectory;
import com.vpn.server.service.P2pSessionRegistry;
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
    @Mock private P2pRelayDirectory relayDirectory;
    @Mock private Authentication auth;

    // Real, not mocked: session ownership is the access rule under test here,
    // and a mock would only assert that the controller calls it.
    private final P2pSessionRegistry sessionRegistry = new P2pSessionRegistry();

    private P2pRelayController controller;

    private static final long CALLER_ID = 7L;
    private static final long OWN_NODE_ID = 100L;
    private static final long SOMEBODY_ELSES_NODE_ID = 200L;

    @BeforeEach
    void setUp() {
        controller = new P2pRelayController(
                userRepository, nodeManagementService, agentStreamService,
                p2pRelayAccountingService, creditRepository, relayDirectory, sessionRegistry);
        lenient().when(auth.getPrincipal()).thenReturn(CALLER_ID);
        lenient().when(nodeManagementService.isOwnRelayDevice(OWN_NODE_ID, CALLER_ID)).thenReturn(true);
        lenient().when(nodeManagementService.isOwnRelayDevice(SOMEBODY_ELSES_NODE_ID, CALLER_ID)).thenReturn(false);
        // Availability is its own rule with its own tests (P2pRelayDirectoryTest);
        // here it is satisfied so the session-ownership rules stay in view.
        lenient().when(relayDirectory.mayConnectThrough(anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void testSignalToAPeerOutsideTheCallersPlanIsRefused() {
        // The lists a client is shown are not the enforcement: a caller can
        // name any node id, so the check has to live here.
        when(relayDirectory.mayConnectThrough(CALLER_ID, SOMEBODY_ELSES_NODE_ID)).thenReturn(false);

        ResponseEntity<?> response = controller.sendSignal(SOMEBODY_ELSES_NODE_ID, signalBody(), auth);

        assertEquals(403, response.getStatusCode().value());
        verify(agentStreamService, never()).sendSignalToNode(anyLong(), anyString(), any());
    }

    private static Map<String, String> signalBody() {
        return Map.of("sessionId", "s-1", "payloadBase64", Base64.getEncoder().encodeToString("offer".getBytes()));
    }

    @Test
    void testSignalToOwnRelayDeviceIsRefusedAndNeverReachesTheNode() {
        ResponseEntity<?> response = controller.sendSignal(OWN_NODE_ID, signalBody(), auth);

        assertEquals(403, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("your own device"), response.getBody().toString());
        verify(agentStreamService, never()).sendSignalToNode(anyLong(), anyString(), any());
    }

    @Test
    void testSignalToSomebodyElsesRelayNodeStillGoesThrough() {
        when(agentStreamService.sendSignalToNode(eq(SOMEBODY_ELSES_NODE_ID), eq("s-1"), any())).thenReturn(true);

        ResponseEntity<?> response = controller.sendSignal(SOMEBODY_ELSES_NODE_ID, signalBody(), auth);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testAnUnreachableRelayIsReportedRatherThanLookingLikeSuccess() {
        // The node went offline, or its relay window lapsed, between being
        // listed and being signalled — the client has to know to pick another
        // one rather than sit waiting for an answer that cannot come.
        when(agentStreamService.sendSignalToNode(eq(SOMEBODY_ELSES_NODE_ID), eq("s-1"), any())).thenReturn(false);

        ResponseEntity<?> response = controller.sendSignal(SOMEBODY_ELSES_NODE_ID, signalBody(), auth);

        assertEquals(503, response.getStatusCode().value());
    }

    @Test
    void testAnotherAccountCannotReadOrInjectIntoSomebodyElsesSession() {
        // Session ids are minted by clients and route the whole negotiation, so
        // without an owner anyone could collect another account's SDP and
        // candidates, or push signals into its session.
        when(agentStreamService.sendSignalToNode(anyLong(), anyString(), any())).thenReturn(true);
        assertEquals(200, controller.sendSignal(SOMEBODY_ELSES_NODE_ID, signalBody(), auth).getStatusCode().value());

        Authentication stranger = mock(Authentication.class);
        when(stranger.getPrincipal()).thenReturn(CALLER_ID + 1);

        assertEquals(403, controller.pollSignals("s-1", 1000, stranger).getStatusCode().value());
        assertEquals(403, controller.sendSignal(SOMEBODY_ELSES_NODE_ID, signalBody(), stranger).getStatusCode().value());
        assertEquals(403, controller.closeSession("s-1", stranger).getStatusCode().value());
        verify(agentStreamService, never()).awaitSignal(eq("s-1"), anyLong());
    }

    @Test
    void testPollingAnUnknownSessionIsRefusedRatherThanOpeningOne() {
        assertEquals(403, controller.pollSignals("never-started", 1000, auth).getStatusCode().value());
    }

    @Test
    void testTheOwnerCollectsWhatTheRelayHasSaid() {
        when(agentStreamService.sendSignalToNode(anyLong(), anyString(), any())).thenReturn(true);
        controller.sendSignal(SOMEBODY_ELSES_NODE_ID, signalBody(), auth);
        when(agentStreamService.awaitSignal(eq("s-1"), anyLong())).thenReturn("answer".getBytes());

        ResponseEntity<?> response = controller.pollSignals("s-1", 1000, auth);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains(Base64.getEncoder().encodeToString("answer".getBytes())));
    }

    @Test
    void testAQuietPollIsAnOrdinaryEmptyAnswerNotAnError() {
        // A client polls in a loop while negotiating; nothing to report yet is
        // the normal case and must not read as a failure.
        when(agentStreamService.sendSignalToNode(anyLong(), anyString(), any())).thenReturn(true);
        controller.sendSignal(SOMEBODY_ELSES_NODE_ID, signalBody(), auth);
        when(agentStreamService.awaitSignal(eq("s-1"), anyLong())).thenReturn(null);

        ResponseEntity<?> response = controller.pollSignals("s-1", 1000, auth);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("payloadBase64"));
    }

    @Test
    void testClientTrafficReportForOwnRelayDeviceIsRefusedAndNotAccounted() {
        // The self-crediting half: pairing this with the relay agent's own
        // report is what would mint credit, so it must not be recorded at all.
        ResponseEntity<?> response = controller.reportSessionTraffic(
                "s-2", Map.of("nodeId", OWN_NODE_ID, "bytesRelayed", 1024), auth);

        assertEquals(403, response.getStatusCode().value());
        verify(p2pRelayAccountingService, never()).recordClientReport(anyLong(), anyString(), anyLong(), any(), anyBoolean());
    }

    @Test
    void testClientTrafficReportForSomebodyElsesNodeIsAccounted() {
        ResponseEntity<?> response = controller.reportSessionTraffic(
                "s-3", Map.of("nodeId", SOMEBODY_ELSES_NODE_ID, "bytesRelayed", 2048), auth);

        assertEquals(200, response.getStatusCode().value());
        verify(p2pRelayAccountingService).recordClientReport(SOMEBODY_ELSES_NODE_ID, "s-3", 2048L, CALLER_ID, false);
    }
}
