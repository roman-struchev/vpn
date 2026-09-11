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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class NodeManagementService {

    private static final Logger log = LoggerFactory.getLogger(NodeManagementService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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

        applyRealityKeyMaterial(node);

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
            node.setCpuCount(heartbeat.getCpuCount());
            node.setMemoryUsedBytes(heartbeat.getMemoryUsedBytes());
            node.setMemoryTotalBytes(heartbeat.getMemoryTotalBytes());
            node.setActiveConnections(heartbeat.getActiveConnections());
            if (heartbeat.getActiveConnections() == 0) {
                // The agent only ever emits a traffic-stats report while it has
                // connected users (AgentGrpcClient#sendTrafficStats returns early
                // when its delta collection is empty), so recentBytesPerSec would
                // otherwise keep showing whatever throughput was last measured
                // — stale — for as long as the node stays idle. That's the same
                // "smeared stale history" failure mode this fix started from,
                // just for throughput instead of CPU load-average. Zero
                // connections is an unambiguous "no traffic right now" signal,
                // so force it to zero immediately rather than wait for a report
                // that will never come.
                node.setRecentBytesPerSec(0.0);
            }
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
        long nodeTotalBytes = 0;
        for (ClientTrafficDelta delta : report.getDeltasList()) {
            long totalBytes = delta.getUplinkBytes() + delta.getDownlinkBytes();
            if (totalBytes <= 0) continue;
            nodeTotalBytes += totalBytes;

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

        long finalNodeTotalBytes = nodeTotalBytes;
        Instant now = Instant.now();
        nodeRepository.findById(nodeId).ifPresent(node -> {
            if (finalNodeTotalBytes > 0) {
                node.setTotalBytesServed(node.getTotalBytesServed() + finalNodeTotalBytes);
            }

            // Recent throughput: bytes reported this round ÷ elapsed time since
            // the previous traffic-stats report — a lightweight "bytes/sec right
            // now" figure, and arguably a more honest "is this node busy" signal
            // for a proxy than CPU alone (see SubscriptionExportService#
            // loadLevelFor). The first report after a gap (or ever) has no prior
            // timestamp to divide by, so it's skipped rather than guessed at;
            // node.recentBytesPerSec simply keeps its previous value (null on a
            // brand-new node) until the next report can compute a real rate.
            Instant previousReportAt = node.getLastTrafficStatsAt();
            if (previousReportAt != null) {
                double elapsedSeconds = Math.max(1.0, Duration.between(previousReportAt, now).toMillis() / 1000.0);
                node.setRecentBytesPerSec(finalNodeTotalBytes / elapsedSeconds);
            }
            node.setLastTrafficStatsAt(now);

            nodeRepository.save(node);
        });
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

    // Not readOnly: ensureRealityKeyMaterial below may backfill and persist
    // key material for a node whose row predates it (self-invocation runs in
    // this same transaction, so it needs an actual flush at commit).
    @Transactional
    public ConfigSync buildNodeConfigSync(Long nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        ensureRealityKeyMaterial(node);

        List<DeviceNodeKey> keys = deviceNodeKeyRepository.findActiveKeysByNodeId(nodeId);
        List<ClientConfig> clients = new ArrayList<>();

        for (DeviceNodeKey key : keys) {
            Device device = key.getDevice();
            User user = device.getUser();

            // Check if user has an active non-exhausted subscription. Blocked accounts
            // (AdminController#updateUserStatus) must lose VPN access immediately, not
            // just stop being billable — this was previously unchecked here, so a
            // BLOCKED user with an unexpired subscription kept working VLESS keys.
            boolean hasActiveSub = "ACTIVE".equalsIgnoreCase(user.getStatus())
                    && subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(user.getId(), "ACTIVE")
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
                .setPrivateKey(node.getRealityPrivateKey() != null ? node.getRealityPrivateKey() : "")
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

    /**
     * REALITY key material is generated once per node and never rotated once
     * present — rotating it would invalidate every subscription link already
     * handed out with the old public key. Mutates {@code node} in place; does
     * not persist (callers that already hold a save/flush of their own, like
     * {@link #registerNode}, fold it into that).
     */
    private boolean applyRealityKeyMaterial(Node node) {
        boolean changed = false;
        if (node.getRealityPublicKey() == null || node.getRealityPrivateKey() == null) {
            RealityKeyPair keyPair = generateRealityKeyPair();
            node.setRealityPrivateKey(keyPair.privateKeyBase64Url());
            node.setRealityPublicKey(keyPair.publicKeyBase64Url());
            changed = true;
        }
        if (node.getRealityShortIds() == null || node.getRealityShortIds().length == 0) {
            node.setRealityShortIds(new String[] { generateShortId(), generateShortId() });
            changed = true;
        }
        return changed;
    }

    /**
     * Backfills REALITY key material for a node that predates it — either a
     * row created before {@link #applyRealityKeyMaterial} existed, or one
     * whose agent process never re-registers because it persists its node
     * token locally and skips the register RPC on restart/reconnect (see
     * agent/src/client/grpc-client.ts AgentGrpcClient#init, agent/src/config.ts
     * loadPersistedState). Called wherever a node's keys are consumed
     * (config sync, subscription export) so such a node is fixed the next
     * time it's touched instead of being stuck with null keys — and an empty
     * "pbk" in every VLESS link it hands out — forever. No-op, no extra write,
     * for a node that already has its keys.
     */
    @Transactional
    public Node ensureRealityKeyMaterial(Node node) {
        // node is mutated in place, so return it directly rather than trust
        // save()'s return value — keeps this robust under simple test doubles
        // that don't stub save() to echo its argument back.
        if (applyRealityKeyMaterial(node)) {
            nodeRepository.save(node);
        }
        return node;
    }

    private record RealityKeyPair(String privateKeyBase64Url, String publicKeyBase64Url) {}

    /**
     * Generates a fresh REALITY (X25519) keypair in the same raw, unpadded
     * base64url encoding {@code xray x25519} produces — verified byte-for-byte
     * against the actual xray-core binary (same private scalar in, same
     * public key out) since Java's X25519 keys are ASN.1-wrapped and don't
     * expose the raw 32-byte values directly.
     */
    private RealityKeyPair generateRealityKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
            generator.initialize(NamedParameterSpec.X25519);
            KeyPair keyPair = generator.generateKeyPair();

            byte[] privateScalar = ((XECPrivateKey) keyPair.getPrivate()).getScalar()
                    .orElseThrow(() -> new IllegalStateException("X25519 provider did not expose a raw private scalar"));

            // XECPublicKey#getU() is a big-endian BigInteger; X25519 encodes the
            // u-coordinate as 32 little-endian bytes, so it has to be reversed
            // (and zero-padded — a small u-coordinate can serialize shorter).
            BigInteger u = ((XECPublicKey) keyPair.getPublic()).getU();
            byte[] uBigEndian = u.toByteArray();
            byte[] publicRaw = new byte[32];
            for (int i = 0; i < Math.min(uBigEndian.length, 32); i++) {
                publicRaw[i] = uBigEndian[uBigEndian.length - 1 - i];
            }

            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new RealityKeyPair(encoder.encodeToString(privateScalar), encoder.encodeToString(publicRaw));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate REALITY X25519 keypair", e);
        }
    }

    /** xray REALITY short IDs: 0-16 hex chars (0-8 bytes) identifying a client config. */
    private String generateShortId() {
        byte[] bytes = new byte[8];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
