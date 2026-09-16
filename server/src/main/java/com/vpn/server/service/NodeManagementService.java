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

        // Reusable within its validity window (ops provisioning several VPS off
        // one token) — expiresAt is the only real gate now, isUsed/usedAt/
        // usedByNode/useCount are updated below purely for admin visibility.
        NodeBootstrapToken bootstrapToken = tokenRepository.findByToken(request.getBootstrapToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid bootstrap token"));

        if (bootstrapToken.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Bootstrap token has expired");
        }

        Node node = nodeRepository.findByHostname(request.getHostname())
                .orElseGet(Node::new);
        // Captured before any setter below runs: a fresh Node::new has no id
        // yet (JPA assigns one only on save), while a row found by hostname
        // already does. Determines whether applyTariffAccessFlags below is
        // allowed to run — see that call site's comment for why.
        boolean isNewNode = node.getId() == null;

        node.setHostname(request.getHostname());
        // Not validated as a real routable address — meaningless for a p2p
        // node (docs/research/P2P_RELAY_FEASIBILITY.md §8.4), only ever a
        // placeholder there. A direct/cdn node still needs a real one for
        // SubscriptionExportService's VLESS link generation to work, but
        // that's the bootstrap operator's responsibility, same as today.
        node.setPublicIp(request.getPublicIp());
        // Only ever set from the token for a brand-new node — see
        // applyTariffAccessFlags's call site below for why an existing
        // node's pool (an admin may have deliberately changed it since) must
        // survive a later re-registration with the same original token.
        if (isNewNode) {
            node.setPool(bootstrapToken.getAssignedPool());
        }
        node.setType(bootstrapToken.getAssignedType());
        node.setRegion(request.getRegion().isBlank() ? "default" : request.getRegion());
        node.setAsn(request.getAsn());
        node.setStatus("ONLINE");
        node.setLastHeartbeatAt(Instant.now());
        // Only ever set from the consumed bootstrap token's own owner — see
        // NodeBootstrapToken#ownerUser's doc for why this is never trusted
        // from the register request itself.
        node.setOwnerUser(bootstrapToken.getOwnerUser());
        // Only ever derives the DEFAULT for a brand-new node — pool itself
        // is also guarded the same way above, so an admin's later pool
        // change (the sole lever for tariff access now) survives a
        // re-registration too. A p2p relay client re-registers on every app
        // restart and every relay-mode OFF -> ON toggle (same hostname, so
        // this finds the existing row, not a new one) — re-deriving here
        // every time would otherwise silently revert an admin's pool change
        // within minutes for exactly the node type that churns the most.
        if (isNewNode) {
            applyTariffAccessFlags(node);
        }
        applyRelayWindow(node, request.getRelayMode(), request.getRelayExpiresAtEpochMs());

        applyRealityKeyMaterial(node);

        node = nodeRepository.save(node);

        bootstrapToken.setIsUsed(true);
        bootstrapToken.setUsedAt(Instant.now());
        bootstrapToken.setUsedByNode(node);
        bootstrapToken.setUseCount(bootstrapToken.getUseCount() + 1);
        tokenRepository.save(bootstrapToken);

        // Revoke any credential(s) left over from a previous registration of this
        // same node (re-registering with a fresh bootstrap token — see
        // scripts/install-node.sh's PREV_TOKEN check) before issuing a new one.
        // Leaving old rows unrevoked let them pile up under one node_id, and
        // authenticateNode's single-result query crashed the gRPC sync stream
        // (NonUniqueResultException) the moment a second one existed.
        Instant revokedNow = Instant.now();
        for (NodeCredential old : credentialRepository.findAllByNodeIdAndRevokedAtIsNull(node.getId())) {
            old.setRevokedAt(revokedNow);
            credentialRepository.save(old);
        }

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

        NodeType assignedType = switch (node.getType().toLowerCase()) {
            case "cdn" -> NodeType.NODE_TYPE_CDN;
            case "p2p" -> NodeType.NODE_TYPE_P2P;
            default -> NodeType.NODE_TYPE_DIRECT;
        };

        return RegisterNodeResponse.newBuilder()
                .setNodeId(node.getId())
                .setNodeToken(rawToken)
                .setAssignedPool(assignedPool)
                .setAssignedType(assignedType)
                .setHeartbeatIntervalSeconds(heartbeatIntervalSec)
                .setStatsIntervalSeconds(statsIntervalSec)
                .build();
    }

    /**
     * Server-side gate for dispatching a P2P signal to a node (docs §8.5) —
     * called from AgentStreamServiceImpl.sendSignalToNodeAndAwaitReply before
     * every forward, not just checked at registration/heartbeat time. Node#
     * isEligibleForRelay() alone is inert unless something actually calls it
     * on the hot path; this is that call site, so a node whose TIMED window
     * has lapsed (or that never enabled relay at all) stops being handed new
     * sessions the moment its window closes, without needing another
     * heartbeat to notice. Also true (a no-op check) for a non-p2p node,
     * though those never receive signals in practice.
     */
    public boolean isNodeEligibleForRelay(Long nodeId) {
        return nodeRepository.findById(nodeId).map(Node::isEligibleForRelay).orElse(false);
    }

    /**
     * Derives the two tariff-access flags purely from `pool` (docs §8.3) —
     * "trial" and "both" grant availableToTrial=true (paid tariffs already
     * reach trial-pool nodes as bonus/fallback capacity — see
     * SubscriptionExportService#accessibleViaFlags — and "both" exists
     * specifically to grant that same dual reach to a node that ISN'T in the
     * trial pool), every other pool value (paid/quarantine/reserve) is
     * paid-only. No longer special-cases type=p2p: a p2p node simply gets
     * pool="both" by convention (see createP2pBootstrapTokenForUser and the
     * admin bootstrap dialog's default), so this one rule now covers every
     * node type — deliberately collapsed from an earlier version that
     * special-cased p2p and exposed a second, independently-editable UI
     * control, which the repo owner found confusing ("зачем доступ по
     * тарифу и пул разными столбцами") — pool is now the single lever.
     *
     * Public (not just called from registerNode above) because
     * AdminController#updateNodePool must also re-derive these flags the
     * moment an admin changes a node's pool — a VPS node may never
     * re-register again in its whole lifetime, so if only registerNode
     * called this, the pool dropdown would silently stop actually changing
     * access for any node that's already running.
     */
    public void applyTariffAccessFlags(Node node) {
        String pool = node.getPool();
        boolean isTrialPool = "trial".equalsIgnoreCase(pool) || "both".equalsIgnoreCase(pool);
        node.setAvailableToTrial(isTrialPool);
        node.setAvailableToPaid(true);
    }

    /**
     * Applies a p2p relay window reported at registration or heartbeat time
     * (docs §8.5). Blank/unset relay_mode is a deliberate no-op (leaves
     * whatever the node already had — e.g. a plain heartbeat that doesn't
     * touch relay state shouldn't reset it to OFF), not an implicit "OFF" —
     * a node explicitly turning relay off must send relay_mode="OFF" itself.
     */
    private void applyRelayWindow(Node node, String relayMode, long relayExpiresAtEpochMs) {
        if (relayMode == null || relayMode.isBlank()) {
            return;
        }
        node.setRelayMode(relayMode.toUpperCase(Locale.ROOT));
        node.setRelayExpiresAt(relayExpiresAtEpochMs > 0 ? Instant.ofEpochMilli(relayExpiresAtEpochMs) : null);
    }

    @Transactional
    public boolean authenticateNode(Long nodeId, String rawToken) {
        if (nodeId == null || rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return credentialRepository.findAllByNodeIdAndRevokedAtIsNull(nodeId).stream()
                .anyMatch(cred -> passwordEncoder.matches(rawToken, cred.getTokenHash()));
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
            applyRelayWindow(node, heartbeat.getRelayMode(), heartbeat.getRelayExpiresAtEpochMs());
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
                            .map(sub -> sub.getTrafficUsedBytes() < sub.getTrafficLimitBytes() && !sub.isExpired())
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
        // p2p relay agents have no client-side implementation yet (phase 2/3,
        // docs/research/P2P_RELAY_FEASIBILITY.md §8.7) — what ConfigSync
        // actually means for one (a local Xray-core reused as-is per §8.8's
        // recommendation, vs. a from-scratch WebRTC↔VLESS bridge) is an open
        // design question for whoever builds that agent, not decided here.
        // This only avoids building the CDN branch's TLS cert-path block
        // below (which references paths that make no sense without a real
        // provisioned certificate) for a p2p node in the meantime.
        boolean isP2p = node.isP2p();

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

        if (!isDirect && !isP2p) {
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

        NodeType assignedType = switch (node.getType().toLowerCase()) {
            case "cdn" -> NodeType.NODE_TYPE_CDN;
            case "p2p" -> NodeType.NODE_TYPE_P2P;
            default -> NodeType.NODE_TYPE_DIRECT;
        };

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

    /**
     * Mints a p2p bootstrap token bound to a specific user (docs §8.4/§8.6) —
     * the caller (P2pRelayController) is responsible for checking the user
     * has actually accepted the relay terms and isn't a guest account first;
     * this method just does the minting + binding, no eligibility checks of
     * its own. Short validity (1h) since it's meant to be consumed
     * immediately by that same user's own relay-agent registering itself,
     * not stockpiled or shared like an ops VPS token.
     */
    @Transactional
    public NodeBootstrapToken createP2pBootstrapTokenForUser(User user) {
        NodeBootstrapToken token = new NodeBootstrapToken();
        token.setToken("bt_p2p_" + UUID.randomUUID().toString().replace("-", ""));
        token.setAssignedPool("both"); // dual trial+paid access is the normal default for a self-serve p2p relay device — see applyTariffAccessFlags
        token.setAssignedType("p2p");
        token.setOwnerUser(user);
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
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
