package com.vpn.server.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "telegram_id", unique = true)
    private Long telegramId;

    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Column(nullable = false, length = 32)
    private String role = "USER";

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "balance_usdt_micro", nullable = false)
    private Long balanceUsdtMicro = 0L;

    @Column(name = "referral_code", nullable = false, unique = true, length = 32)
    private String referralCode;

    // Opaque identifier for the public, unauthenticated subscription export URL
    // (GET /api/v1/subscription/export/{token}) — never the raw sequential id,
    // see V2__anti_enumeration.sql. The DB column also defaults to
    // gen_random_uuid() (backfills rows from before this migration); the Java
    // default below covers new rows explicitly, since Hibernate sends every
    // mapped column on INSERT and would otherwise override the DB default
    // with NULL.
    @Column(name = "subscription_token", nullable = false, unique = true)
    private UUID subscriptionToken = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_by_user_id")
    private User referredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public User() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Long getTelegramId() { return telegramId; }
    public void setTelegramId(Long telegramId) { this.telegramId = telegramId; }

    public String getGoogleSub() { return googleSub; }
    public void setGoogleSub(String googleSub) { this.googleSub = googleSub; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getBalanceUsdtMicro() { return balanceUsdtMicro; }
    public void setBalanceUsdtMicro(Long balanceUsdtMicro) { this.balanceUsdtMicro = balanceUsdtMicro; }

    public String getReferralCode() { return referralCode; }
    public void setReferralCode(String referralCode) { this.referralCode = referralCode; }

    public UUID getSubscriptionToken() { return subscriptionToken; }
    public void setSubscriptionToken(UUID subscriptionToken) { this.subscriptionToken = subscriptionToken; }

    public User getReferredBy() { return referredBy; }
    public void setReferredBy(User referredBy) { this.referredBy = referredBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void preUpdate() { this.updatedAt = Instant.now(); }
}
