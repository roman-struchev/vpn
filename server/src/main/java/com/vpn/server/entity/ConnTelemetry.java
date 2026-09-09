package com.vpn.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "conn_telemetry")
public class ConnTelemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id")
    private Node node;

    @Column(nullable = false, length = 64)
    private String operator;

    @Column(nullable = false, length = 64)
    private String region;

    @Column(nullable = false, length = 32)
    private String transport;

    @Column(name = "connect_time_ms", nullable = false)
    private Integer connectTimeMs;

    @Column(name = "failure_count", nullable = false)
    private Integer failureCount = 0;

    @Column(name = "is_whitelist_suspected", nullable = false)
    private Boolean isWhitelistSuspected = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ConnTelemetry() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Node getNode() { return node; }
    public void setNode(Node node) { this.node = node; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public Integer getConnectTimeMs() { return connectTimeMs; }
    public void setConnectTimeMs(Integer connectTimeMs) { this.connectTimeMs = connectTimeMs; }

    public Integer getFailureCount() { return failureCount; }
    public void setFailureCount(Integer failureCount) { this.failureCount = failureCount; }

    public Boolean getIsWhitelistSuspected() { return isWhitelistSuspected; }
    public void setIsWhitelistSuspected(Boolean isWhitelistSuspected) { this.isWhitelistSuspected = isWhitelistSuspected; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
