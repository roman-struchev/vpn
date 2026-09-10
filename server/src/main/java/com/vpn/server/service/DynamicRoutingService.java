package com.vpn.server.service;

import com.vpn.server.entity.ConnTelemetry;
import com.vpn.server.entity.Node;
import com.vpn.server.entity.TransportPolicy;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.ConnTelemetryRepository;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.TransportPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DynamicRoutingService {

    private static final Logger log = LoggerFactory.getLogger(DynamicRoutingService.class);

    private final TransportPolicyRepository transportPolicyRepository;
    private final ConnTelemetryRepository connTelemetryRepository;
    private final NodeRepository nodeRepository;
    private final AgentStreamServiceImpl agentStreamService;

    @org.springframework.beans.factory.annotation.Value("${vpn.grpc-fallback.port:8443}")
    private int grpcFallbackPort;

    @org.springframework.beans.factory.annotation.Value("${vpn.grpc-fallback.service-name:vless-grpc}")
    private String grpcFallbackServiceName;

    public DynamicRoutingService(
            TransportPolicyRepository transportPolicyRepository,
            ConnTelemetryRepository connTelemetryRepository,
            NodeRepository nodeRepository,
            AgentStreamServiceImpl agentStreamService
    ) {
        this.transportPolicyRepository = transportPolicyRepository;
        this.connTelemetryRepository = connTelemetryRepository;
        this.nodeRepository = nodeRepository;
        this.agentStreamService = agentStreamService;
    }

    public record RoutingConfigResponse(
            String primaryTransport,
            String fallbackTransport,
            String fingerprint,
            int backoffInitialSec,
            int maxRetriesBeforeNodeSwitch,
            List<NodeInfo> nodes
    ) {
        public record NodeInfo(
                Long id,
                String publicIp,
                int vlessPort,
                String region,
                String sni,
                // Phase 9: gRPC+Reality fallback inbound, same node/keys, different port —
                // set only for "direct" nodes (see NodeManagementService.buildNodeConfigSync).
                Integer grpcFallbackPort,
                String grpcFallbackServiceName
        ) {}
    }

    @Transactional(readOnly = true)
    public RoutingConfigResponse getRoutingConfig(String operator, String region) {
        TransportPolicy policy = resolvePolicy(operator, region);

        List<Node> activeNodes = nodeRepository.findByPoolAndStatus("paid", "ONLINE");
        if (activeNodes.isEmpty()) {
            activeNodes = nodeRepository.findByStatus("ONLINE");
        }

        // Filter out quarantine and reserve pools (reserve nodes are standby-only
        // until DynamicRoutingService promotes them — see checkAndQuarantineNode).
        List<RoutingConfigResponse.NodeInfo> nodeInfos = activeNodes.stream()
                .filter(n -> !"quarantine".equalsIgnoreCase(n.getPool()) && !"reserve".equalsIgnoreCase(n.getPool()))
                .map(n -> {
                    boolean isDirect = "direct".equalsIgnoreCase(n.getType());
                    return new RoutingConfigResponse.NodeInfo(
                            n.getId(),
                            n.getPublicIp(),
                            443,
                            n.getRegion(),
                            "dl.google.com",
                            isDirect ? grpcFallbackPort : null,
                            isDirect ? grpcFallbackServiceName : null
                    );
                })
                .toList();

        return new RoutingConfigResponse(
                policy.getPrimaryTransport(),
                policy.getFallbackTransport(),
                policy.getFingerprint(),
                policy.getBackoffInitialSec(),
                policy.getMaxRetriesBeforeNodeSwitch(),
                nodeInfos
        );
    }

    @Transactional
    public void recordTelemetry(
            Long nodeId,
            String operator,
            String region,
            String transport,
            int connectTimeMs,
            int failureCount,
            boolean isWhitelistSuspected
    ) {
        ConnTelemetry telemetry = new ConnTelemetry();
        if (nodeId != null) {
            nodeRepository.findById(nodeId).ifPresent(telemetry::setNode);
        }
        telemetry.setOperator(operator != null ? operator : "UNKNOWN");
        telemetry.setRegion(region != null ? region : "UNKNOWN");
        telemetry.setTransport(transport != null ? transport : "XHTTP");
        telemetry.setConnectTimeMs(connectTimeMs);
        telemetry.setFailureCount(failureCount);
        telemetry.setIsWhitelistSuspected(isWhitelistSuspected);
        telemetry.setCreatedAt(Instant.now());

        connTelemetryRepository.save(telemetry);

        // Automated quarantine check
        if (nodeId != null && (failureCount >= 3 || isWhitelistSuspected)) {
            checkAndQuarantineNode(nodeId);
        }
    }

    private void checkAndQuarantineNode(Long nodeId) {
        Node node = nodeRepository.findById(nodeId).orElse(null);
        if (node == null || "quarantine".equalsIgnoreCase(node.getPool())) {
            return;
        }

        Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
        List<ConnTelemetry> recent = connTelemetryRepository.findRecent(tenMinutesAgo);

        long nodeFailures = recent.stream()
                .filter(t -> t.getNode() != null && t.getNode().getId().equals(nodeId))
                .filter(t -> t.getFailureCount() >= 3 || Boolean.TRUE.equals(t.getIsWhitelistSuspected()))
                .count();

        if (nodeFailures >= 5) {
            String previousPool = node.getPool();
            log.warn("Node {} ({}) reached {} severe connection failures in 10m. Moving to QUARANTINE pool.",
                    node.getId(), node.getPublicIp(), nodeFailures);
            node.setPool("quarantine");
            nodeRepository.save(node);
            promoteReserveNode(node, previousPool);
            agentStreamService.pushConfigSyncToAll();
        }
    }

    /**
     * Phase 9 "backup address pool rotation": when a node is quarantined,
     * automatically promote a standby node from the 'reserve' pool (same
     * region) into the pool the quarantined node just vacated, so paid/trial
     * capacity doesn't silently shrink while waiting for a human to react.
     * New client keys for the promoted node are created lazily the next time
     * a user fetches their subscription links (same mechanism already used
     * for any newly added node — see SubscriptionExportService).
     */
    private void promoteReserveNode(Node quarantinedNode, String previousPool) {
        List<Node> reserves = nodeRepository.findByPoolAndRegionAndStatus("reserve", quarantinedNode.getRegion(), "ONLINE");
        if (reserves.isEmpty()) {
            log.warn("No reserve-pool node available in region {} to replace quarantined node {}",
                    quarantinedNode.getRegion(), quarantinedNode.getId());
            return;
        }
        Node promoted = reserves.get(0);
        promoted.setPool(previousPool);
        nodeRepository.save(promoted);
        log.warn("Promoted reserve node {} ({}) into '{}' pool to replace quarantined node {} in region {}",
                promoted.getId(), promoted.getPublicIp(), previousPool, quarantinedNode.getId(), quarantinedNode.getRegion());
    }

    private TransportPolicy resolvePolicy(String operator, String region) {
        if (operator != null && !operator.isBlank()) {
            Optional<TransportPolicy> opPolicy = transportPolicyRepository
                    .findFirstByScopeAndScopeValueAndIsActiveTrue("operator", operator.trim());
            if (opPolicy.isPresent()) return opPolicy.get();
        }

        if (region != null && !region.isBlank()) {
            Optional<TransportPolicy> regPolicy = transportPolicyRepository
                    .findFirstByScopeAndScopeValueAndIsActiveTrue("region", region.trim());
            if (regPolicy.isPresent()) return regPolicy.get();
        }

        return transportPolicyRepository
                .findFirstByScopeAndScopeValueAndIsActiveTrue("global", "*")
                .orElseGet(this::createDefaultFallbackPolicy);
    }

    private TransportPolicy createDefaultFallbackPolicy() {
        TransportPolicy p = new TransportPolicy();
        p.setScope("global");
        p.setScopeValue("*");
        p.setPrimaryTransport("XHTTP");
        p.setFallbackTransport("GRPC");
        p.setFingerprint("firefox");
        p.setBackoffInitialSec(15);
        p.setMaxRetriesBeforeNodeSwitch(3);
        return p;
    }
}
