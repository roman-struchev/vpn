package com.vpn.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "balance_entries")
public class BalanceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @JsonIgnore: not serialized by any controller today, but Device.user and
    // CryptoInvoice.user (same LAZY @ManyToOne shape) both caused a real
    // "works until you have one row, then 500s forever" bug the moment they
    // were — kept consistent pre-emptively so a future listing endpoint here
    // doesn't reintroduce it.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "amount_usdt_micro", nullable = false)
    private Long amountUsdtMicro;

    @Column(name = "balance_after_micro", nullable = false)
    private Long balanceAfterMicro;

    @Column(nullable = false, length = 32)
    private String type; // DEPOSIT, SUBSCRIPTION_DEBIT, REFUND, REFERRAL_BONUS, MANUAL_ADJUSTMENT

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "reference_id", length = 128)
    private String referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public BalanceEntry() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Long getAmountUsdtMicro() { return amountUsdtMicro; }
    public void setAmountUsdtMicro(Long amountUsdtMicro) { this.amountUsdtMicro = amountUsdtMicro; }

    public Long getBalanceAfterMicro() { return balanceAfterMicro; }
    public void setBalanceAfterMicro(Long balanceAfterMicro) { this.balanceAfterMicro = balanceAfterMicro; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
