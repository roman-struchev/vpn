package com.vpn.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * One credited P2P relay session (docs/research/P2P_RELAY_FEASIBILITY.md
 * §8.2) — written only after the relay node's and the connecting client's
 * independent byte-count self-reports agreed within tolerance
 * (P2pRelayAccountingService). Byte-denominated rather than a BalanceEntry:
 * see V11 migration's comment for why a USDT conversion rate isn't picked
 * here.
 */
@Entity
@Table(name = "p2p_relay_credits")
public class P2pRelayCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_node_id")
    @JsonIgnore
    private Node relayNode;

    @Column(name = "bytes_relayed", nullable = false)
    private Long bytesRelayed;

    @Column(name = "bytes_credited", nullable = false)
    private Long bytesCredited;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public P2pRelayCredit() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Node getRelayNode() { return relayNode; }
    public void setRelayNode(Node relayNode) { this.relayNode = relayNode; }

    public Long getBytesRelayed() { return bytesRelayed; }
    public void setBytesRelayed(Long bytesRelayed) { this.bytesRelayed = bytesRelayed; }

    public Long getBytesCredited() { return bytesCredited; }
    public void setBytesCredited(Long bytesCredited) { this.bytesCredited = bytesCredited; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
