package com.vpn.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "nodes")
public class Node {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String hostname;

    @Column(name = "public_ip", nullable = false, length = 64)
    private String publicIp;

    @Column(nullable = false, length = 32)
    private String status = "OFFLINE"; // ONLINE, OFFLINE, DRAINING, MAINTENANCE

    @Column(nullable = false, length = 32)
    private String pool = "paid"; // trial, paid, quarantine, reserve (standby — see DynamicRoutingService#promoteReserveNode)

    @Column(nullable = false, length = 32)
    private String type = "direct"; // direct, cdn, p2p (see docs/research/P2P_RELAY_FEASIBILITY.md §8)

    // Tariff access, independent of the `pool` lifecycle column above (which
    // stays ONLINE/quarantine/reserve-style grouping, untouched by this).
    // A regular VPS node keeps exactly one of these true, matching its old
    // exclusive pool; a P2P node can have both true at once — see V11
    // migration's backfill for how existing nodes were mapped onto this.
    @Column(name = "available_to_trial", nullable = false)
    private Boolean availableToTrial = false;

    @Column(name = "available_to_paid", nullable = false)
    private Boolean availableToPaid = false;

    // P2P relay window (docs §8.5) — meaningless for a regular VPS node
    // (stays "OFF" forever). A P2P node is only ever handed out to a
    // connecting client while eligible per isEligibleForRelay() below;
    // enforced here server-side rather than trusting the relaying client to
    // stop offering itself on time.
    @Column(name = "relay_mode", nullable = false, length = 16)
    private String relayMode = "OFF"; // OFF, TIMED, ALWAYS

    @Column(name = "relay_expires_at")
    private Instant relayExpiresAt; // meaningful only when relayMode = TIMED

    // Who a p2p node's relayed traffic is credited to — copied from the
    // consumed bootstrap token's own ownerUser at registration time (see
    // NodeManagementService#registerNode), never self-declared by the
    // register request itself. Null for every ops-deployed VPS node.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    @Column(nullable = false, length = 64)
    private String region;

    @Column(length = 64)
    private String asn;

    @Column(name = "reality_public_key", length = 128)
    private String realityPublicKey;

    // Never sent to clients — only the node's own xray inbound needs this,
    // via NodeManagementService#buildNodeConfigSync's RealityConfig.
    @Column(name = "reality_private_key", length = 128)
    private String realityPrivateKey;

    @Column(name = "reality_short_ids", columnDefinition = "text[]")
    private String[] realityShortIds;

    @Column(name = "current_config_hash", length = 64)
    private String currentConfigHash;

    @Column(name = "config_version", nullable = false)
    private Long configVersion = 1L;

    @Column(name = "cpu_percent", precision = 5, scale = 2)
    private BigDecimal cpuPercent;

    @Column(name = "memory_used_bytes")
    private Long memoryUsedBytes;

    @Column(name = "memory_total_bytes")
    private Long memoryTotalBytes;

    @Column(name = "active_connections")
    private Integer activeConnections = 0;

    @Column(name = "cpu_count")
    private Integer cpuCount;

    @Column(name = "total_bytes_served", nullable = false)
    private Long totalBytesServed = 0L;

    // Bytes/sec since the previous traffic-stats report — see
    // NodeManagementService#processTrafficStats. Forced to 0 the moment a
    // heartbeat reports activeConnections == 0 (NodeManagementService#
    // processHeartbeat), so it can't keep showing stale throughput from a
    // now-ended burst the way the old CPU load-average figure used to.
    @Column(name = "recent_bytes_per_sec")
    private Double recentBytesPerSec;

    // Bookkeeping only (elapsed-time denominator for the next traffic-stats
    // report) — not surfaced anywhere.
    @Column(name = "last_traffic_stats_at")
    private Instant lastTrafficStatsAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Node() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPool() { return pool; }
    public void setPool(String pool) { this.pool = pool; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Boolean getAvailableToTrial() { return availableToTrial; }
    public void setAvailableToTrial(Boolean availableToTrial) { this.availableToTrial = availableToTrial; }

    public Boolean getAvailableToPaid() { return availableToPaid; }
    public void setAvailableToPaid(Boolean availableToPaid) { this.availableToPaid = availableToPaid; }

    public String getRelayMode() { return relayMode; }
    public void setRelayMode(String relayMode) { this.relayMode = relayMode; }

    public Instant getRelayExpiresAt() { return relayExpiresAt; }
    public void setRelayExpiresAt(Instant relayExpiresAt) { this.relayExpiresAt = relayExpiresAt; }

    // @JsonIgnore: Node is serialized as-is by AdminController#listNodes — a
    // LAZY relation like this one throws on serialization outside an open
    // Hibernate session (see BalanceEntry.user/Device.user for the same
    // pattern). ownerUserId below is the safe, scalar equivalent for admin
    // visibility.
    @JsonIgnore
    public User getOwnerUser() { return ownerUser; }
    public void setOwnerUser(User ownerUser) { this.ownerUser = ownerUser; }

    public Long getOwnerUserId() { return ownerUser != null ? ownerUser.getId() : null; }

    public boolean isP2p() { return "p2p".equalsIgnoreCase(type); }

    /**
     * Whether this node should currently be handed out to a connecting
     * client at all. Always true for a non-p2p node (a VPS node's
     * relayMode/relayExpiresAt are meaningless — its ONLINE status is the
     * only gate). For a p2p node: ALWAYS mode, or TIMED with a still-future
     * window — never trusts the client to have stopped offering itself on
     * time (docs §8.5).
     */
    public boolean isEligibleForRelay() {
        if (!isP2p()) return true;
        if ("ALWAYS".equalsIgnoreCase(relayMode)) return true;
        return "TIMED".equalsIgnoreCase(relayMode) && relayExpiresAt != null && relayExpiresAt.isAfter(Instant.now());
    }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAsn() { return asn; }
    public void setAsn(String asn) { this.asn = asn; }

    public String getRealityPublicKey() { return realityPublicKey; }
    public void setRealityPublicKey(String realityPublicKey) { this.realityPublicKey = realityPublicKey; }

    @JsonIgnore
    public String getRealityPrivateKey() { return realityPrivateKey; }
    public void setRealityPrivateKey(String realityPrivateKey) { this.realityPrivateKey = realityPrivateKey; }

    public String[] getRealityShortIds() { return realityShortIds; }
    public void setRealityShortIds(String[] realityShortIds) { this.realityShortIds = realityShortIds; }

    public String getCurrentConfigHash() { return currentConfigHash; }
    public void setCurrentConfigHash(String currentConfigHash) { this.currentConfigHash = currentConfigHash; }

    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }

    public BigDecimal getCpuPercent() { return cpuPercent; }
    public void setCpuPercent(BigDecimal cpuPercent) { this.cpuPercent = cpuPercent; }

    public Long getMemoryUsedBytes() { return memoryUsedBytes; }
    public void setMemoryUsedBytes(Long memoryUsedBytes) { this.memoryUsedBytes = memoryUsedBytes; }

    public Long getMemoryTotalBytes() { return memoryTotalBytes; }
    public void setMemoryTotalBytes(Long memoryTotalBytes) { this.memoryTotalBytes = memoryTotalBytes; }

    public Integer getActiveConnections() { return activeConnections; }
    public void setActiveConnections(Integer activeConnections) { this.activeConnections = activeConnections; }

    public Integer getCpuCount() { return cpuCount; }
    public void setCpuCount(Integer cpuCount) { this.cpuCount = cpuCount; }

    public Long getTotalBytesServed() { return totalBytesServed; }
    public void setTotalBytesServed(Long totalBytesServed) { this.totalBytesServed = totalBytesServed; }

    public Double getRecentBytesPerSec() { return recentBytesPerSec; }
    public void setRecentBytesPerSec(Double recentBytesPerSec) { this.recentBytesPerSec = recentBytesPerSec; }

    public Instant getLastTrafficStatsAt() { return lastTrafficStatsAt; }
    public void setLastTrafficStatsAt(Instant lastTrafficStatsAt) { this.lastTrafficStatsAt = lastTrafficStatsAt; }

    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void preUpdate() { this.updatedAt = Instant.now(); }
}
