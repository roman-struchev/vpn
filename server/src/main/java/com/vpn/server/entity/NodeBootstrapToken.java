package com.vpn.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "node_bootstrap_tokens")
public class NodeBootstrapToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String token;

    @Column(name = "assigned_pool", nullable = false, length = 32)
    private String assignedPool = "paid";

    @Column(name = "assigned_type", nullable = false, length = 32)
    private String assignedType = "direct";

    // No longer an exclusivity gate (see registerNode) — "has this token ever
    // been used" plus usedAt/usedByNode as "most recent use", both for admin
    // visibility. useCount is the source of truth for how many times it's
    // actually been redeemed.
    @Column(name = "is_used", nullable = false)
    private Boolean isUsed = false;

    @Column(name = "used_at")
    private Instant usedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by_node_id")
    private Node usedByNode;

    @Column(name = "use_count", nullable = false)
    private Integer useCount = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public NodeBootstrapToken() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getAssignedPool() { return assignedPool; }
    public void setAssignedPool(String assignedPool) { this.assignedPool = assignedPool; }

    public String getAssignedType() { return assignedType; }
    public void setAssignedType(String assignedType) { this.assignedType = assignedType; }

    public Boolean getIsUsed() { return isUsed; }
    public void setIsUsed(Boolean isUsed) { this.isUsed = isUsed; }

    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }

    public Node getUsedByNode() { return usedByNode; }
    public void setUsedByNode(Node usedByNode) { this.usedByNode = usedByNode; }

    public Integer getUseCount() { return useCount; }
    public void setUseCount(Integer useCount) { this.useCount = useCount; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
