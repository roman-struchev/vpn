package com.vpn.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "crypto_invoices")
public class CryptoInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 32)
    private String chain; // TRON, ETHEREUM, BASE, ARBITRUM

    @Column(nullable = false, length = 32)
    private String token = "USDT";

    @Column(name = "base_amount_usdt_micro", nullable = false)
    private Long baseAmountUsdtMicro;

    @Column(name = "delta_step_micro", nullable = false)
    private Integer deltaStepMicro;

    @Column(name = "expected_amount_usdt_micro", nullable = false)
    private Long expectedAmountUsdtMicro;

    @Column(name = "tolerance_min_micro", nullable = false)
    private Long toleranceMinMicro;

    @Column(name = "tolerance_max_micro", nullable = false)
    private Long toleranceMaxMicro;

    @Column(name = "recipient_address", nullable = false)
    private String recipientAddress;

    @Column(nullable = false, length = 32)
    private String status = "PENDING"; // PENDING, PAID, EXPIRED, CANCELLED

    @Column(name = "tx_hash")
    private String txHash;

    @Column(name = "actual_amount_usdt_micro")
    private Long actualAmountUsdtMicro;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "paid_at")
    private Instant paidAt;

    public CryptoInvoice() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getChain() { return chain; }
    public void setChain(String chain) { this.chain = chain; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Long getBaseAmountUsdtMicro() { return baseAmountUsdtMicro; }
    public void setBaseAmountUsdtMicro(Long baseAmountUsdtMicro) { this.baseAmountUsdtMicro = baseAmountUsdtMicro; }

    public Integer getDeltaStepMicro() { return deltaStepMicro; }
    public void setDeltaStepMicro(Integer deltaStepMicro) { this.deltaStepMicro = deltaStepMicro; }

    public Long getExpectedAmountUsdtMicro() { return expectedAmountUsdtMicro; }
    public void setExpectedAmountUsdtMicro(Long expectedAmountUsdtMicro) { this.expectedAmountUsdtMicro = expectedAmountUsdtMicro; }

    public Long getToleranceMinMicro() { return toleranceMinMicro; }
    public void setToleranceMinMicro(Long toleranceMinMicro) { this.toleranceMinMicro = toleranceMinMicro; }

    public Long getToleranceMaxMicro() { return toleranceMaxMicro; }
    public void setToleranceMaxMicro(Long toleranceMaxMicro) { this.toleranceMaxMicro = toleranceMaxMicro; }

    public String getRecipientAddress() { return recipientAddress; }
    public void setRecipientAddress(String recipientAddress) { this.recipientAddress = recipientAddress; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public Long getActualAmountUsdtMicro() { return actualAmountUsdtMicro; }
    public void setActualAmountUsdtMicro(Long actualAmountUsdtMicro) { this.actualAmountUsdtMicro = actualAmountUsdtMicro; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
}
