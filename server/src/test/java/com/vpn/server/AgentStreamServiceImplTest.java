package com.vpn.server;

import com.google.protobuf.ByteString;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.service.DiagnosticsService;
import com.vpn.server.grpc.agent.v1.*;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.service.P2pRelayAccountingService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the P2P signaling passthrough (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.1) — the server only ever routes an opaque
 * blob by session_id between a connecting client (the REST caller of
 * sendSignalToNodeAndAwaitReply) and a relay node's existing stream; it
 * never parses payload. No real client or relay agent exists yet (phase
 * 2/3), so this mocks both ends of the stream to exercise the routing logic
 * in isolation.
 */
class AgentStreamServiceImplTest {

    private NodeManagementService nodeManagementService;
    private P2pRelayAccountingService p2pRelayAccountingService;
    private AgentStreamServiceImpl service;

    @BeforeEach
    void setUp() {
        nodeManagementService = mock(NodeManagementService.class);
        p2pRelayAccountingService = mock(P2pRelayAccountingService.class);
        service = new AgentStreamServiceImpl(nodeManagementService, p2pRelayAccountingService, mock(DiagnosticsService.class));
    }

    @SuppressWarnings("unchecked")
    private StreamObserver<AgentMessage> connectNode(long nodeId, StreamObserver<ServerMessage> responseObserver) {
        when(nodeManagementService.authenticateNode(nodeId, "tok")).thenReturn(true);
        when(nodeManagementService.buildNodeConfigSync(nodeId)).thenReturn(ConfigSync.newBuilder().build());
        StreamObserver<AgentMessage> clientStream = service.syncStream(responseObserver);
        clientStream.onNext(AgentMessage.newBuilder().setNodeId(nodeId).setNodeToken("tok").build());
        return clientStream;
    }

    @Test
    void sendSignalToNodeAndAwaitReply_returnsNull_whenNodeNotConnected() {
        byte[] reply = service.sendSignalToNodeAndAwaitReply(999L, "sess-none", "hi".getBytes());
        assertNull(reply);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendSignalToNodeAndAwaitReply_forwardsToNodeStream_andReturnsItsReply() {
        List<ServerMessage> sentToNode = new CopyOnWriteArrayList<>();
        StreamObserver<ServerMessage> responseObserver = mock(StreamObserver.class);
        doAnswer(inv -> {
            sentToNode.add(inv.getArgument(0));
            return null;
        }).when(responseObserver).onNext(any());

        StreamObserver<AgentMessage> nodeStream = connectNode(7L, responseObserver);
        when(nodeManagementService.isNodeEligibleForRelay(7L)).thenReturn(true);

        // sendSignalToNodeAndAwaitReply blocks the calling thread waiting for
        // the node's reply — simulate the relay node replying shortly after,
        // on a separate thread, the way a real one would over its own stream.
        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            nodeStream.onNext(AgentMessage.newBuilder()
                    .setNodeId(7L).setNodeToken("tok")
                    .setP2PSignal(P2pSignal.newBuilder()
                            .setSessionId("sess-x")
                            .setPayload(ByteString.copyFromUtf8("answer-sdp"))
                            .build())
                    .build());
        }).start();

        byte[] reply = service.sendSignalToNodeAndAwaitReply(7L, "sess-x", "offer-sdp".getBytes());

        assertNotNull(reply);
        assertEquals("answer-sdp", new String(reply));
        assertTrue(sentToNode.stream().anyMatch(m -> m.hasP2PSignal() && "sess-x".equals(m.getP2PSignal().getSessionId())),
                "the offer must have actually been forwarded over the node's own stream");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendSignalToNodeAndAwaitReply_returnsNull_whenNodeNotEligibleForRelay() {
        StreamObserver<ServerMessage> responseObserver = mock(StreamObserver.class);
        connectNode(10L, responseObserver);
        // Connected but not eligible (e.g. relayMode=OFF, or a TIMED window
        // that already lapsed) — must never forward, regardless of stream state.
        when(nodeManagementService.isNodeEligibleForRelay(10L)).thenReturn(false);

        byte[] reply = service.sendSignalToNodeAndAwaitReply(10L, "sess-ineligible", "offer".getBytes());

        assertNull(reply);
        verify(responseObserver, never()).onNext(argThat(m -> m.hasP2PSignal()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void p2pSignalWithNoWaitingClient_isIgnoredWithoutError() {
        StreamObserver<ServerMessage> responseObserver = mock(StreamObserver.class);
        StreamObserver<AgentMessage> nodeStream = connectNode(8L, responseObserver);

        // No matching sendSignalToNodeAndAwaitReply call for this session —
        // e.g. the REST caller already timed out and moved on. Must not throw.
        assertDoesNotThrow(() -> nodeStream.onNext(AgentMessage.newBuilder()
                .setNodeId(8L).setNodeToken("tok")
                .setP2PSignal(P2pSignal.newBuilder().setSessionId("orphan-session").setPayload(ByteString.EMPTY).build())
                .build()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void p2pTrafficReport_routedToAccountingService() {
        StreamObserver<ServerMessage> responseObserver = mock(StreamObserver.class);
        StreamObserver<AgentMessage> nodeStream = connectNode(9L, responseObserver);

        nodeStream.onNext(AgentMessage.newBuilder()
                .setNodeId(9L).setNodeToken("tok")
                .setP2PTrafficReport(P2pSessionTrafficReport.newBuilder().setSessionId("sess-y").setBytesRelayed(123L).build())
                .build());

        verify(p2pRelayAccountingService).recordRelayNodeReport(9L, "sess-y", 123L);
    }

    @Test
    void signalsFromManyRequestThreadsReachTheNodeStreamOneAtATime() throws Exception {
        // A client sends its offer and a burst of ICE candidates in parallel,
        // each on its own HTTP thread. gRPC's StreamObserver is not
        // thread-safe; overlapping onNext calls got the node's stream
        // cancelled on the real server.
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean overlapped = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger delivered = new java.util.concurrent.atomic.AtomicInteger();
        StreamObserver<ServerMessage> nodeSide = new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage value) {
                if (inFlight.incrementAndGet() > 1) overlapped.set(true);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ignored) {
                }
                inFlight.decrementAndGet();
                delivered.incrementAndGet();
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
        connectNode(11L, nodeSide);
        when(nodeManagementService.isNodeEligibleForRelay(11L)).thenReturn(true);
        int before = delivered.get(); // the initial config sync

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.List<java.util.concurrent.Future<Boolean>> sends = new java.util.ArrayList<>();
        for (int i = 0; i < 64; i++) {
            final int n = i;
            sends.add(pool.submit(() -> service.sendSignalToNode(11L, "sess-" + (n % 4), ("c" + n).getBytes())));
        }
        for (java.util.concurrent.Future<Boolean> f : sends) assertTrue(f.get());
        pool.shutdown();

        assertFalse(overlapped.get(), "onNext must never run concurrently on one node's stream");
        assertEquals(before + 64, delivered.get());
    }
}
