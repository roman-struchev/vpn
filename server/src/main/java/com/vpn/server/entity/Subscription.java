package com.vpn.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @JsonIgnore: not serialized by any controller today (UserController.getProfile
    // builds its own Map instead of returning the entity) — see Device.user for why
    // this is kept consistent pre-emptively rather than only fixed where it already bit.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tariff_id", nullable = false)
    private Tariff tariff;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE"; // ACTIVE, EXPIRED, CANCELLED

    @Column(name = "is_annual", nullable = false)
    private Boolean isAnnual = false;

    @Column(name = "auto_renew", nullable = false)
    private Boolean autoRenew = true;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Column(name = "traffic_used_bytes", nullable = false)
    private Long trafficUsedBytes = 0L;

    @Column(name = "traffic_limit_bytes", nullable = false)
    private Long trafficLimitBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Subscription() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Tariff getTariff() { return tariff; }
    public void setTariff(Tariff tariff) { this.tariff = tariff; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getIsAnnual() { return isAnnual; }
    public void setIsAnnual(Boolean isAnnual) { this.isAnnual = isAnnual; }

    public Boolean getAutoRenew() { return autoRenew; }
    public void setAutoRenew(Boolean autoRenew) { this.autoRenew = autoRenew; }

    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(Instant currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }

    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }

    public Long getTrafficUsedBytes() { return trafficUsedBytes; }
    public void setTrafficUsedBytes(Long trafficUsedBytes) { this.trafficUsedBytes = trafficUsedBytes; }

    public Long getTrafficLimitBytes() { return trafficLimitBytes; }
    public void setTrafficLimitBytes(Long trafficLimitBytes) { this.trafficLimitBytes = trafficLimitBytes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void preUpdate() { this.updatedAt = Instant.now(); }
}
