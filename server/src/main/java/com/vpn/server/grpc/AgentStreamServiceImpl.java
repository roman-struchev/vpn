package com.vpn.server.grpc;

import com.vpn.server.grpc.agent.v1.*;
import com.vpn.server.service.NodeManagementService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentStreamServiceImpl extends AgentStreamServiceGrpc.AgentStreamServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamServiceImpl.class);

    private final NodeManagementService nodeManagementService;
    // Map of active node streams: nodeId -> StreamObserver<ServerMessage>
    private final Map<Long, StreamObserver<ServerMessage>> activeStreams = new ConcurrentHashMap<>();

    public AgentStreamServiceImpl(NodeManagementService nodeManagementService) {
        this.nodeManagementService = nodeManagementService;
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
}
