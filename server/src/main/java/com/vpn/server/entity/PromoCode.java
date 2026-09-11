package com.vpn.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "promo_codes")
public class PromoCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "bonus_amount_usdt_micro", nullable = false)
    private Long bonusAmountUsdtMicro;

    @Column(name = "max_activations")
    private Integer maxActivations;

    @Column(name = "activations_count", nullable = false)
    private int activationsCount = 0;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public PromoCode() {}

    public PromoCode(String code, Long bonusAmountUsdtMicro, Integer maxActivations, Instant expiresAt) {
        this.code = code.toUpperCase().trim();
        this.bonusAmountUsdtMicro = bonusAmountUsdtMicro;
        this.maxActivations = maxActivations;
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code != null ? code.toUpperCase().trim() : null; }

    public Long getBonusAmountUsdtMicro() { return bonusAmountUsdtMicro; }
    public void setBonusAmountUsdtMicro(Long bonusAmountUsdtMicro) { this.bonusAmountUsdtMicro = bonusAmountUsdtMicro; }

    public Integer getMaxActivations() { return maxActivations; }
    public void setMaxActivations(Integer maxActivations) { this.maxActivations = maxActivations; }

    public int getActivationsCount() { return activationsCount; }
    public void setActivationsCount(int activationsCount) { this.activationsCount = activationsCount; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
