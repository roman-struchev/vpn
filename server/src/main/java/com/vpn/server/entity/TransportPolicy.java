package com.vpn.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "transport_policies")
public class TransportPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String scope; // global, region, operator, user

    @Column(name = "scope_value", nullable = false, length = 128)
    private String scopeValue; // '*', 'RU-MOW', 'MTS', etc.

    @Column(name = "primary_transport", nullable = false, length = 32)
    private String primaryTransport = "XHTTP";

    @Column(name = "fallback_transport", nullable = false, length = 32)
    private String fallbackTransport = "GRPC";

    @Column(nullable = false, length = 32)
    private String fingerprint = "firefox";

    @Column(name = "backoff_initial_sec", nullable = false)
    private Integer backoffInitialSec = 15;

    @Column(name = "max_retries_before_node_switch", nullable = false)
    private Integer maxRetriesBeforeNodeSwitch = 3;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public TransportPolicy() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getScopeValue() { return scopeValue; }
    public void setScopeValue(String scopeValue) { this.scopeValue = scopeValue; }

    public String getPrimaryTransport() { return primaryTransport; }
    public void setPrimaryTransport(String primaryTransport) { this.primaryTransport = primaryTransport; }

    public String getFallbackTransport() { return fallbackTransport; }
    public void setFallbackTransport(String fallbackTransport) { this.fallbackTransport = fallbackTransport; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public Integer getBackoffInitialSec() { return backoffInitialSec; }
    public void setBackoffInitialSec(Integer backoffInitialSec) { this.backoffInitialSec = backoffInitialSec; }

    public Integer getMaxRetriesBeforeNodeSwitch() { return maxRetriesBeforeNodeSwitch; }
    public void setMaxRetriesBeforeNodeSwitch(Integer maxRetriesBeforeNodeSwitch) { this.maxRetriesBeforeNodeSwitch = maxRetriesBeforeNodeSwitch; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void preUpdate() { this.updatedAt = Instant.now(); }
}
