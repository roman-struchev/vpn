package com.vpn.server.service;

import com.vpn.server.entity.*;
import com.vpn.server.grpc.agent.v1.*;
import com.vpn.server.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class NodeManagementService {

    private static final Logger log = LoggerFactory.getLogger(NodeManagementService.class);

    private final NodeRepository nodeRepository;
    private final NodeBootstrapTokenRepository tokenRepository;
    private final NodeCredentialRepository credentialRepository;
    private final DeviceNodeKeyRepository deviceNodeKeyRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ConnTelemetryRepository telemetryRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${vpn.reality.dest:dl.google.com:443}")
    private String defaultRealityDest;

    @Value("${vpn.reality.server-names:dl.google.com,gateway.icloud.com}")
    private String defaultServerNames;

    @Value("${vpn.heartbeat-interval-sec:30}")
    private int heartbeatIntervalSec;

    @Value("${vpn.stats-interval-sec:30}")
    private int statsIntervalSec;

    @Value("${vpn.grpc-fallback.port:8443}")
    private int grpcFallbackPort;

    @Value("${vpn.grpc-fallback.service-name:vless-grpc}")
    private String grpcFallbackServiceName;

    @Value("${vpn.cdn.cert-dir:/etc/xray/certs}")
    private String cdnCertDir;

    public NodeManagementService(
            NodeRepository nodeRepository,
            NodeBootstrapTokenRepository tokenRepository,
            NodeCredentialRepository credentialRepository,
            DeviceNodeKeyRepository deviceNodeKeyRepository,
            SubscriptionRepository subscriptionRepository,
            ConnTelemetryRepository telemetryRepository,
            PasswordEncoder passwordEncoder) {
        this.nodeRepository = nodeRepository;
        this.tokenRepository = tokenRepository;
        this.credentialRepository = credentialRepository;
        this.deviceNodeKeyRepository = deviceNodeKeyRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.telemetryRepository = telemetryRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegisterNodeResponse registerNode(RegisterNodeRequest request) {
        log.info("Node registration attempt for hostname: {}, publicIp: {}", request.getHostname(), request.getPublicIp());

        NodeBootstrapToken bootstrapToken = tokenRepository.findByTokenAndIsUsedFalse(request.getBootstrapToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid or already used bootstrap token"));

        if (bootstrapToken.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Bootstrap token has expired");
        }

        Node node = nodeRepository.findByHostname(request.getHostname())
                .orElseGet(Node::new);

        node.setHostname(request.getHostname());
        node.setPublicIp(request.getPublicIp());
        node.setPool(bootstrapToken.getAssignedPool());
        node.setType(bootstrapToken.getAssignedType());
        node.setRegion(request.getRegion().isBlank() ? "default" : request.getRegion());
        node.setAsn(request.getAsn());
        node.setStatus("ONLINE");
        node.setLastHeartbeatAt(Instant.now());
        node = nodeRepository.save(node);

        bootstrapToken.setIsUsed(true);
        bootstrapToken.setUsedAt(Instant.now());
        bootstrapToken.setUsedByNode(node);
        tokenRepository.save(bootstrapToken);

        // Generate persistent node authentication token
        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        NodeCredential credential = new NodeCredential();
        credential.setNode(node);
        credential.setTokenHash(passwordEncoder.encode(rawToken));
        credentialRepository.save(credential);

        NodePool assignedPool = switch (node.getPool().toLowerCase()) {
            case "trial" -> NodePool.NODE_POOL_TRIAL;
            case "quarantine" -> NodePool.NODE_POOL_QUARANTINE;
            default -> NodePool.NODE_POOL_PAID;
        };

        NodeType assignedType = "cdn".equalsIgnoreCase(node.getType()) ? NodeType.NODE_TYPE_CDN : NodeType.NODE_TYPE_DIRECT;

        return RegisterNodeResponse.newBuilder()
                .setNodeId(node.getId())
                .setNodeToken(rawToken)
                .setAssignedPool(assignedPool)
                .setAssignedType(assignedType)
                .setHeartbeatIntervalSeconds(heartbeatIntervalSec)
                .setStatsIntervalSeconds(statsIntervalSec)
                .build();
    }

    @Transactional
    public boolean authenticateNode(Long nodeId, String rawToken) {
        if (nodeId == null || rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return credentialRepository.findByNodeIdAndRevokedAtIsNull(nodeId)
                .map(cred -> passwordEncoder.matches(rawToken, cred.getTokenHash()))
                .orElse(false);
    }

    @Transactional
    public void processHeartbeat(Long nodeId, Heartbeat heartbeat) {
        nodeRepository.findById(nodeId).ifPresent(node -> {
            node.setCpuPercent(BigDecimal.valueOf(heartbeat.getCpuPercent()));
            node.setMemoryUsedBytes(heartbeat.getMemoryUsedBytes());
            node.setMemoryTotalBytes(heartbeat.getMemoryTotalBytes());
            node.setActiveConnections(heartbeat.getActiveConnections());
            node.setStatus("ONLINE");
            node.setLastHeartbeatAt(Instant.now());
            nodeRepository.save(node);
        });
    }

    @Transactional
    public void processConfigAck(Long nodeId, ConfigAck ack) {
        nodeRepository.findById(nodeId).ifPresent(node -> {
            if (ack.getStatus() == AckStatus.ACK_STATUS_SUCCESS) {
                node.setCurrentConfigHash(ack.getConfigHash());
                node.setConfigVersion(ack.getConfigVersion());
                nodeRepository.save(node);
                log.info("Node {} acknowledged config version {} (hash: {})", nodeId, ack.getConfigVersion(), ack.getConfigHash());
            } else {
                log.error("Node {} failed to apply config version {}: {}", nodeId, ack.getConfigVersion(), ack.getErrorMessage());
            }
        });
    }

    @Transactional
    public void processTrafficStats(Long nodeId, TrafficStatsReport report) {
        for (ClientTrafficDelta delta : report.getDeltasList()) {
            long totalBytes = delta.getUplinkBytes() + delta.getDownlinkBytes();
            if (totalBytes <= 0) continue;

            subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(delta.getUserId(), "ACTIVE")
                    .ifPresent(sub -> {
                        long newTotal = sub.getTrafficUsedBytes() + totalBytes;
                        sub.setTrafficUsedBytes(newTotal);
                        if (newTotal >= sub.getTrafficLimitBytes()) {
                            log.warn("Subscription {} for user {} exceeded traffic limit: {}/{}",
                                    sub.getId(), delta.getUserId(), newTotal, sub.getTrafficLimitBytes());
                        }
                        subscriptionRepository.save(sub);
                    });
        }
    }

    @Transactional
    public void processConnTelemetry(Long nodeId, ConnTelemetryReport report) {
        Node node = nodeRepository.findById(nodeId).orElse(null);
        List<ConnTelemetry> records = new ArrayList<>();
        for (ConnTelemetryRecord r : report.getRecordsList()) {
            ConnTelemetry record = new ConnTelemetry();
            record.setNode(node);
            record.setOperator(r.getOperator());
            record.setRegion(r.getRegion());
            record.setTransport(r.getTransport());
            record.setConnectTimeMs(r.getConnectTimeMs());
            record.setFailureCount(r.getFailureCount());
            record.setIsWhitelistSuspected(r.getIsWhitelistSuspected());
            records.add(record);
        }
        telemetryRepository.saveAll(records);
    }

    @Transactional(readOnly = true)
    public ConfigSync buildNodeConfigSync(Long nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));

        List<DeviceNodeKey> keys = deviceNodeKeyRepository.findActiveKeysByNodeId(nodeId);
        List<ClientConfig> clients = new ArrayList<>();

        for (DeviceNodeKey key : keys) {
            Device device = key.getDevice();
            User user = device.getUser();

            // Check if user has an active non-exhausted subscription
            boolean hasActiveSub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(user.getId(), "ACTIVE")
                    .map(sub -> sub.getTrafficUsedBytes() < sub.getTrafficLimitBytes() && sub.getCurrentPeriodEnd().isAfter(Instant.now()))
                    .orElse(false);

            clients.add(ClientConfig.newBuilder()
                    .setUserId(user.getId())
                    .setDeviceId(device.getId())
                    .setUuid(key.getUuid().toString())
                    .setEmailTag("user_" + user.getId() + "_dev_" + device.getId())
                    .setIsActive(hasActiveSub && Boolean.TRUE.equals(device.getIsActive()))
                    .build());
        }

        List<String> serverNames = Arrays.stream(defaultServerNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<String> shortIds = node.getRealityShortIds() != null
                ? Arrays.asList(node.getRealityShortIds())
                : List.of("0123456789abcdef", "fedcba9876543210");

        boolean isDirect = "direct".equalsIgnoreCase(node.getType());

        RealityConfig realityConfig = RealityConfig.newBuilder()
                .setEnabled(isDirect)
                .setDest(defaultRealityDest)
                .addAllServerNames(serverNames)
                .setPrivateKey(node.getRealityPublicKey() != null ? node.getRealityPublicKey() : "")
                .addAllShortIds(shortIds)
                .build();

        XhttpSettings xhttpSettings = XhttpSettings.newBuilder()
                .setPath("/vless-xhttp")
                .setMode("auto")
                .build();

        InboundConfig.Builder inboundBuilder = InboundConfig.newBuilder()
                .setListenPort(443)
                .setProtocol("vless")
                .setTransport("xhttp")
                .setReality(realityConfig)
                .setXhttpSettings(xhttpSettings);

        if (!isDirect) {
            // CDN nodes: the CDN terminates TLS itself, so Reality (which needs an
            // unmodified handshake to the origin) doesn't apply here — use a real
            // certificate instead. Certs are provisioned out-of-band on the node
            // (scripts/install-node.sh's optional certbot step); the server only
            // references the resulting file paths by convention.
            inboundBuilder.setTlsSettings(TlsSettings.newBuilder()
                    .setEnabled(true)
                    .setServerName(node.getHostname())
                    .setCertPath(cdnCertDir + "/" + node.getHostname() + "/fullchain.pem")
                    .setKeyPath(cdnCertDir + "/" + node.getHostname() + "/privkey.pem")
                    .build());
        }

        ConfigSync.Builder syncBuilder = ConfigSync.newBuilder()
                .setInbound(inboundBuilder.build());

        if (isDirect) {
            // gRPC+Reality fallback (docs/ROADMAP_PROGRESS.md Phase 9 / PLAN.md §6:
            // "VLESS + gRPC + Reality — запасной"): reuses the same Reality key
            // material and client identities as the primary XHTTP inbound, just on
            // a different port, so clients can switch transport without a new node
            // or new keys. Not offered for CDN nodes — a CDN's own transport
            // framing already replaces the role gRPC would play here.
            InboundConfig grpcInbound = InboundConfig.newBuilder()
                    .setListenPort(grpcFallbackPort)
                    .setProtocol("vless")
                    .setTransport("grpc")
                    .setReality(realityConfig)
                    .setGrpcSettings(GrpcSettings.newBuilder().setServiceName(grpcFallbackServiceName).build())
                    .build();
            syncBuilder.setFallbackInbound(grpcInbound);
        }

        long version = node.getConfigVersion() + 1;
        String hash = computeConfigHash(version, clients.size(), node.getType());

        NodeType assignedType = "cdn".equalsIgnoreCase(node.getType()) ? NodeType.NODE_TYPE_CDN : NodeType.NODE_TYPE_DIRECT;

        return syncBuilder
                .setConfigVersion(version)
                .setConfigHash(hash)
                .setNodeType(assignedType)
                .addAllClients(clients)
                .build();
    }

    @Transactional
    public NodeBootstrapToken createBootstrapToken(String pool, String type, int validHours) {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_" + UUID.randomUUID().toString().replace("-", ""));
        token.setAssignedPool(pool != null ? pool : "paid");
        token.setAssignedType(type != null ? type : "direct");
        token.setExpiresAt(Instant.now().plus(validHours > 0 ? validHours : 24, ChronoUnit.HOURS));
        return tokenRepository.save(token);
    }

    private String computeConfigHash(long version, int clientCount, String type) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = version + ":" + clientCount + ":" + type + ":" + Instant.now().getEpochSecond();
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }
}
