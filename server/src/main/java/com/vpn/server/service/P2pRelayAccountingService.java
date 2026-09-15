package com.vpn.server.service;

import com.vpn.server.entity.Node;
import com.vpn.server.entity.P2pRelayCredit;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.User;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.P2pRelayCreditRepository;
import com.vpn.server.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-checked accounting for P2P relay traffic credits (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.2). The server never sees the actual relayed
 * bytes (they go client↔peer directly, not through us — that's the whole
 * point of the P2P design) — instead, both the relaying node and the
 * connecting client independently self-report their byte count for a given
 * session_id, and a credit is only ever issued once both reports have
 * arrived and agree within {@link #TOLERANCE}. Making both sides lie in
 * agreement requires collusion, which is a meaningfully higher bar than a
 * single dishonest self-report — not airtight, hence the hard daily cap on
 * top as a backstop against a more determined false-agreement scheme.
 */
@Service
public class P2pRelayAccountingService {

    private static final Logger log = LoggerFactory.getLogger(P2pRelayAccountingService.class);

    /** 1 GB relayed -> 0.5 GB-equivalent credited (docs §8.2 — an explicit product decision, not derived from any tariff's $/GB price). */
    static final double CREDIT_RATE = 0.5;

    /** How far apart the two independent self-reports may be and still be trusted (docs §8.2: "например ±5%, с учётом накладных расходов протокола"). */
    static final double TOLERANCE = 0.05;

    /** Hard daily cap on *credited* (post-rate) bytes per user (docs §8.2: 50GB/day/account, not per device/node). */
    public static final long DAILY_CAP_BYTES = 50L * 1024 * 1024 * 1024;

    private final NodeRepository nodeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final P2pRelayCreditRepository creditRepository;

    /** One session's two self-reports, merged as they arrive — order-independent (either side can report first). */
    private record PendingReport(Long nodeId, Long nodeBytes, Long clientBytes) {}

    // Deliberately in-memory, not persisted: this is short-lived correlation
    // state for one active session's handshake, not an audit trail (the
    // audit trail is P2pRelayCreditRepository, written only on success). A
    // session whose second report never arrives (e.g. the connecting client
    // vanishes) just leaks one small map entry rather than corrupting
    // anything — acceptable for this phase; a background sweep for
    // long-stale entries is a reasonable follow-up, not implemented here.
    private final Map<String, PendingReport> pendingReports = new ConcurrentHashMap<>();

    // Self-injected proxy reference, used only to call tryCredit() below. A
    // plain `this.tryCredit(...)` call from merge() is a same-class
    // self-invocation, which bypasses Spring's proxy-based @Transactional
    // interception entirely (a well-known AOP gotcha) — going through the
    // proxy via this field is what actually makes the credit-save and
    // subscription-save atomic. @Lazy breaks the circular-construction
    // dependency this would otherwise create.
    @Autowired
    @Lazy
    private P2pRelayAccountingService self;

    public P2pRelayAccountingService(
            NodeRepository nodeRepository,
            SubscriptionRepository subscriptionRepository,
            P2pRelayCreditRepository creditRepository) {
        this.nodeRepository = nodeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.creditRepository = creditRepository;
    }

    /** Called from AgentStreamServiceImpl when a p2p relay node reports its side of a session. */
    public void recordRelayNodeReport(Long nodeId, String sessionId, long bytesRelayed) {
        merge(sessionId, nodeId, bytesRelayed, null);
    }

    /** Called from P2pRelayController when the connecting client reports its side of a session. */
    public void recordClientReport(Long nodeId, String sessionId, long bytesRelayed) {
        merge(sessionId, nodeId, null, bytesRelayed);
    }

    private synchronized void merge(String sessionId, Long nodeId, Long nodeBytes, Long clientBytes) {
        PendingReport existing = pendingReports.get(sessionId);
        if (existing != null && !existing.nodeId().equals(nodeId)) {
            log.warn("P2P session {} reported with mismatched nodeId ({} vs {}) — discarding, not crediting", sessionId, existing.nodeId(), nodeId);
            pendingReports.remove(sessionId);
            return;
        }

        Long mergedNodeBytes = nodeBytes != null ? nodeBytes : (existing != null ? existing.nodeBytes() : null);
        Long mergedClientBytes = clientBytes != null ? clientBytes : (existing != null ? existing.clientBytes() : null);

        if (mergedNodeBytes == null || mergedClientBytes == null) {
            pendingReports.put(sessionId, new PendingReport(nodeId, mergedNodeBytes, mergedClientBytes));
            return;
        }

        pendingReports.remove(sessionId);
        // self is null when this service is constructed directly (e.g. plain
        // `new P2pRelayAccountingService(...)` in a unit test, outside a
        // Spring context) — fall back to a direct call there, since without a
        // container there is no proxy to route through anyway.
        P2pRelayAccountingService target = self != null ? self : this;
        target.tryCredit(sessionId, nodeId, mergedNodeBytes, mergedClientBytes);
    }

    @Transactional
    void tryCredit(String sessionId, Long nodeId, long nodeBytes, long clientBytes) {
        if (creditRepository.findBySessionId(sessionId).isPresent()) {
            log.debug("P2P session {} already credited — ignoring duplicate report pair", sessionId);
            return;
        }

        long larger = Math.max(nodeBytes, clientBytes);
        long smaller = Math.min(nodeBytes, clientBytes);
        if (larger > 0 && (larger - smaller) / (double) larger > TOLERANCE) {
            log.warn("P2P session {} reports disagree beyond tolerance (node={}, client={}) — not crediting", sessionId, nodeBytes, clientBytes);
            return;
        }

        Node node = nodeRepository.findById(nodeId).orElse(null);
        User owner = node != null ? node.getOwnerUser() : null;
        if (owner == null) {
            log.warn("P2P session {} on node {} has no owning user — not crediting", sessionId, nodeId);
            return;
        }

        // Conservative: credit off the smaller of the two reports, never the
        // larger, so an over-reporting side can never inflate its own payout
        // even within tolerance.
        long relayedBytes = smaller;
        long creditBytes = Math.round(relayedBytes * CREDIT_RATE);

        long alreadyCreditedToday = creditRepository.sumBytesCreditedSince(owner.getId(), Instant.now().minus(1, ChronoUnit.DAYS));
        long remainingCapBytes = DAILY_CAP_BYTES - alreadyCreditedToday;
        if (remainingCapBytes <= 0) {
            log.info("P2P session {}: user {} already at/over the daily {}GB relay-credit cap — not crediting", sessionId, owner.getId(), DAILY_CAP_BYTES / (1024 * 1024 * 1024));
            return;
        }
        long finalCreditBytes = Math.min(creditBytes, remainingCapBytes);
        if (finalCreditBytes <= 0) {
            return;
        }

        P2pRelayCredit credit = new P2pRelayCredit();
        credit.setUser(owner);
        credit.setSessionId(sessionId);
        credit.setRelayNode(node);
        credit.setBytesRelayed(relayedBytes);
        credit.setBytesCredited(finalCreditBytes);
        creditRepository.save(credit);

        // Credited directly onto the owner's own current traffic quota
        // (byte-for-byte, not a monetary balance — see V11 migration's
        // comment for why) rather than a separate always-available pool, so
        // it's immediately usable the same way any other traffic headroom is.
        Optional<Subscription> sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(owner.getId(), "ACTIVE");
        sub.ifPresentOrElse(
                s -> {
                    s.setTrafficLimitBytes(s.getTrafficLimitBytes() + finalCreditBytes);
                    subscriptionRepository.save(s);
                },
                () -> log.warn("P2P session {}: user {} earned a credit but has no active subscription to apply it to", sessionId, owner.getId())
        );

        log.info("P2P session {} credited: user {} relayed {} bytes -> {} bytes credited (node {})",
                sessionId, owner.getId(), relayedBytes, finalCreditBytes, nodeId);
    }
}
