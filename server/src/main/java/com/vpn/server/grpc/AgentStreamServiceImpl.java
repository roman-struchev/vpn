package com.vpn.server.grpc;

import com.vpn.server.grpc.agent.v1.*;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.service.P2pRelayAccountingService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class AgentStreamServiceImpl extends AgentStreamServiceGrpc.AgentStreamServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamServiceImpl.class);

    private final NodeManagementService nodeManagementService;
    private final P2pRelayAccountingService p2pRelayAccountingService;
    // Map of active node streams: nodeId -> StreamObserver<ServerMessage>
    private final Map<Long, StreamObserver<ServerMessage>> activeStreams = new ConcurrentHashMap<>();

    // Pure message-routing state for P2P signaling (docs/research/
    // P2P_RELAY_FEASIBILITY.md §8.1) — the server never parses/understands
    // the payload, it's just a broker between a connecting client (REST,
    // UserController's p2p endpoints — ordinary clients hold no AgentStream)
    // and a relay node's existing stream. A session_id is minted by the
    // connecting client when it starts signaling to a given node; a signal
    // arriving here from the NODE side (its answer/ICE) is stashed until the
    // client's poll picks it up, or it expires unclaimed.
    private final Map<String, CompletableFuture<byte[]>> pendingClientSignals = new ConcurrentHashMap<>();
    private static final long SIGNAL_WAIT_TIMEOUT_SECONDS = 15;

    public AgentStreamServiceImpl(NodeManagementService nodeManagementService, P2pRelayAccountingService p2pRelayAccountingService) {
        this.nodeManagementService = nodeManagementService;
        this.p2pRelayAccountingService = p2pRelayAccountingService;
    }

    @Override
    public StreamObserver<AgentMessage> syncStream(StreamObserver<ServerMessage> responseObserver) {
        return new StreamObserver<>() {
            private Long authenticatedNodeId = null;

            @Override
            public void onNext(AgentMessage message) {
                Long nodeId = message.getNodeId();
                String token = message.getNodeToken();

                if (authenticatedNodeId == null) {
                    if (!nodeManagementService.authenticateNode(nodeId, token)) {
                        log.warn("Unauthenticated stream connection attempt from node {}", nodeId);
                        responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Invalid node credentials").asRuntimeException());
                        return;
                    }
                    authenticatedNodeId = nodeId;
                    activeStreams.put(nodeId, responseObserver);
                    log.info("Node {} connected to live sync stream", nodeId);

                    // Send initial configuration sync to the node
                    try {
                        ConfigSync initialConfig = nodeManagementService.buildNodeConfigSync(nodeId);
                        ServerMessage syncMsg = ServerMessage.newBuilder()
                                .setTimestampEpochMs(System.currentTimeMillis())
                                .setConfigSync(initialConfig)
                                .build();
                        responseObserver.onNext(syncMsg);
                    } catch (Exception e) {
                        log.error("Failed to build initial config for node {}", nodeId, e);
                    }
                } else if (!authenticatedNodeId.equals(nodeId)) {
                    log.warn("Node ID mismatch on established stream: expected {}, got {}", authenticatedNodeId, nodeId);
                    responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Node ID mismatch").asRuntimeException());
                    return;
                }

                switch (message.getPayloadCase()) {
                    case HEARTBEAT -> {
                        nodeManagementService.processHeartbeat(nodeId, message.getHeartbeat());
                        ServerMessage ack = ServerMessage.newBuilder()
                                .setTimestampEpochMs(System.currentTimeMillis())
                                .setHeartbeatAck(HeartbeatAck.newBuilder()
                                        .setServerTimeEpochMs(System.currentTimeMillis())
                                        .build())
                                .build();
                        responseObserver.onNext(ack);
                    }
                    case CONFIG_ACK -> nodeManagementService.processConfigAck(nodeId, message.getConfigAck());
                    case TRAFFIC_STATS -> nodeManagementService.processTrafficStats(nodeId, message.getTrafficStats());
                    case CONN_TELEMETRY -> nodeManagementService.processConnTelemetry(nodeId, message.getConnTelemetry());
                    case P2P_SIGNAL -> resolvePendingClientSignal(message.getP2PSignal());
                    case P2P_TRAFFIC_REPORT -> p2pRelayAccountingService.recordRelayNodeReport(
                            nodeId, message.getP2PTrafficReport().getSessionId(), message.getP2PTrafficReport().getBytesRelayed());
                    case PAYLOAD_NOT_SET -> log.debug("Empty message received from node {}", nodeId);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Stream error on node {}: {}", authenticatedNodeId, t.getMessage());
                if (authenticatedNodeId != null) {
                    activeStreams.remove(authenticatedNodeId);
                }
            }

            @Override
            public void onCompleted() {
                log.info("Stream completed by node {}", authenticatedNodeId);
                if (authenticatedNodeId != null) {
                    activeStreams.remove(authenticatedNodeId);
                }
                responseObserver.onCompleted();
            }
        };
    }

    public void pushConfigSync(Long nodeId) {
        StreamObserver<ServerMessage> observer = activeStreams.get(nodeId);
        if (observer != null) {
            try {
                ConfigSync sync = nodeManagementService.buildNodeConfigSync(nodeId);
                ServerMessage msg = ServerMessage.newBuilder()
                        .setTimestampEpochMs(System.currentTimeMillis())
                        .setConfigSync(sync)
                        .build();
                observer.onNext(msg);
                log.info("Pushed updated config sync to node {}", nodeId);
            } catch (Exception e) {
                log.error("Failed to push config sync to node {}", nodeId, e);
            }
        }
    }

    public void pushConfigSyncToAll() {
        for (Long nodeId : activeStreams.keySet()) {
            pushConfigSync(nodeId);
        }
    }

    /**
     * Forwards a connecting client's SDP offer / ICE candidate to a p2p relay
     * node's existing stream, then waits (bounded) for that node's reply on
     * the same session_id. Pure passthrough — payload is opaque to the
     * server on both legs (docs §8.1). Returns null on timeout or if the
     * node isn't currently connected; callers (UserController's p2p signal
     * endpoint) surface that as "try another node" rather than an error the
     * client can't act on.
     */
    public byte[] sendSignalToNodeAndAwaitReply(Long nodeId, String sessionId, byte[] payload) {
        // The actual enforcement point for Node#isEligibleForRelay (docs
        // §8.5) — a node's relay window/eligibility is otherwise just stored
        // state nothing consults, letting an already-expired TIMED node (or
        // one that never turned relay on) keep answering signals until its
        // next heartbeat happens to overwrite relayMode. Checked here, on
        // every dispatch, not only at registration/heartbeat time.
        if (!nodeManagementService.isNodeEligibleForRelay(nodeId)) {
            log.warn("P2P signal for session {} rejected — node {} is not currently eligible for relay", sessionId, nodeId);
            return null;
        }

        StreamObserver<ServerMessage> observer = activeStreams.get(nodeId);
        if (observer == null) {
            log.warn("P2P signal for session {} could not be sent — node {} has no active stream", sessionId, nodeId);
            return null;
        }

        CompletableFuture<byte[]> pending = new CompletableFuture<>();
        pendingClientSignals.put(sessionId, pending);
        try {
            ServerMessage msg = ServerMessage.newBuilder()
                    .setTimestampEpochMs(System.currentTimeMillis())
                    .setP2PSignal(P2pSignal.newBuilder()
                            .setSessionId(sessionId)
                            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                            .build())
                    .build();
            observer.onNext(msg);
            return pending.get(SIGNAL_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("P2P signal round-trip for session {} on node {} failed/timed out: {}", sessionId, nodeId, e.getMessage());
            return null;
        } finally {
            pendingClientSignals.remove(sessionId);
        }
    }

    private void resolvePendingClientSignal(P2pSignal signal) {
        CompletableFuture<byte[]> pending = pendingClientSignals.get(signal.getSessionId());
        if (pending != null) {
            pending.complete(signal.getPayload().toByteArray());
        } else {
            log.debug("P2P signal for session {} arrived with no (or an already-timed-out) waiting client", signal.getSessionId());
        }
    }

    public boolean sendCommand(Long nodeId, ServerCommand command) {
        StreamObserver<ServerMessage> observer = activeStreams.get(nodeId);
        if (observer != null) {
            try {
                ServerMessage msg = ServerMessage.newBuilder()
                        .setTimestampEpochMs(System.currentTimeMillis())
                        .setCommand(command)
                        .build();
                observer.onNext(msg);
                log.info("Sent command {} to node {}", command.getCommandType(), nodeId);
                return true;
            } catch (Exception e) {
                log.error("Failed to send command to node {}", nodeId, e);
            }
        }
        return false;
    }
}
