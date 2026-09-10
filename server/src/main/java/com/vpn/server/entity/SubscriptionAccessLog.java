package com.vpn.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One row per subscription-link/export request, used by AntiEnumerationService
 * to detect a single account being pulled from an unusually large number of
 * distinct IPs in a short window (docs/ROADMAP_PROGRESS.md Phase 10 /
 * PLAN.md §6).
 */
@Entity
@Table(name = "subscription_access_log")
public class SubscriptionAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ip_address", nullable = false, length = 64)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public SubscriptionAccessLog() {}

    public SubscriptionAccessLog(Long userId, String ipAddress) {
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
