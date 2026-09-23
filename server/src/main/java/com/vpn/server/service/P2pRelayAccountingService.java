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

    /**
     * One session's two self-reports, merged as they arrive — order-independent
     * (either side can report first).
     *
     * {@code connectingUserId} and {@code exitSession} only ever come from the
     * client's half (the relay node knows neither: it is handed an opaque
     * session id and an address, and deliberately never learns whose traffic
     * it is carrying or whether that address is one of our nodes).
     */
    private record PendingReport(Long nodeId, Long nodeBytes, Long clientBytes,
                                 Long connectingUserId, boolean exitSession) {}

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
        merge(sessionId, nodeId, bytesRelayed, null, null, false);
    }

    /**
     * Called from P2pRelayController when the connecting client reports its
     * side of a session. {@code exitSession} is the client saying it used this
     * peer as an exit rather than as a path to one of our nodes — see
     * {@link #tryCredit} for what that changes and how far it is trusted.
     */
    public void recordClientReport(Long nodeId, String sessionId, long bytesRelayed,
                                   Long connectingUserId, boolean exitSession) {
        merge(sessionId, nodeId, null, bytesRelayed, connectingUserId, exitSession);
    }

    private synchronized void merge(String sessionId, Long nodeId, Long nodeBytes, Long clientBytes,
                                    Long connectingUserId, boolean exitSession) {
        PendingReport existing = pendingReports.get(sessionId);
        if (existing != null && !existing.nodeId().equals(nodeId)) {
            log.warn("P2P session {} reported with mismatched nodeId ({} vs {}) — discarding, not crediting", sessionId, existing.nodeId(), nodeId);
            pendingReports.remove(sessionId);
            return;
        }

        Long mergedNodeBytes = nodeBytes != null ? nodeBytes : (existing != null ? existing.nodeBytes() : null);
        Long mergedClientBytes = clientBytes != null ? clientBytes : (existing != null ? existing.clientBytes() : null);
        Long mergedUserId = connectingUserId != null ? connectingUserId : (existing != null ? existing.connectingUserId() : null);
        boolean mergedExit = exitSession || (existing != null && existing.exitSession());

        if (mergedNodeBytes == null || mergedClientBytes == null) {
            pendingReports.put(sessionId, new PendingReport(nodeId, mergedNodeBytes, mergedClientBytes, mergedUserId, mergedExit));
            return;
        }

        pendingReports.remove(sessionId);
        // self is null when this service is constructed directly (e.g. plain
        // `new P2pRelayAccountingService(...)` in a unit test, outside a
        // Spring context) — fall back to a direct call there, since without a
        // container there is no proxy to route through anyway.
        P2pRelayAccountingService target = self != null ? self : this;
        target.tryCredit(sessionId, nodeId, mergedNodeBytes, mergedClientBytes, mergedUserId, mergedExit);
    }

    /**
     * Highest agreed total already settled per session, for sessions with no
     * credit row to remember it (no owner, owner at the daily cap). Bounded:
     * a session that falls out simply starts from its credit row, or from 0.
     */
    private final Map<String, Long> settledBytesBySession = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 20_000;
                }
            });

    /**
     * Settles one agreed pair of CUMULATIVE reports. Both sides report
     * periodically with a running total (every relay agent, and the
     * connecting clients too), so a session is settled in increments: each
     * new agreed total settles only what it adds over the last one.
     *
     * It used to settle a session once, ever — the first agreed pair, often
     * from the first few seconds — and ignore every later one. A long P2P
     * exit session was then charged to the user's quota, and paid to the
     * peer, for its first report only. And the exit charge came after the
     * owner/cap checks, so a peer with no owner, or one whose owner hit the
     * daily credit cap, carried traffic nobody was charged for. Metering the
     * user now comes first and does not depend on whether anyone is paid.
     */
    @Transactional
    void tryCredit(String sessionId, Long nodeId, long nodeBytes, long clientBytes,
                   Long connectingUserId, boolean exitSession) {
        long larger = Math.max(nodeBytes, clientBytes);
        long smaller = Math.min(nodeBytes, clientBytes);
        if (larger > 0 && (larger - smaller) / (double) larger > TOLERANCE) {
            log.warn("P2P session {} reports disagree beyond tolerance (node={}, client={}) — not crediting", sessionId, nodeBytes, clientBytes);
            return;
        }

        // Conservative: settle off the smaller of the two reports, never the
        // larger, so an over-reporting side can never inflate its own payout
        // even within tolerance.
        long agreedTotal = smaller;
        Optional<P2pRelayCredit> existing = creditRepository.findBySessionId(sessionId);
        long alreadySettled = Math.max(
                existing.map(P2pRelayCredit::getBytesRelayed).orElse(0L),
                settledBytesBySession.getOrDefault(sessionId, 0L));
        long deltaBytes = agreedTotal - alreadySettled;
        if (deltaBytes <= 0) {
            log.debug("P2P session {}: nothing new since the last settled total ({})", sessionId, alreadySettled);
            return;
        }
        settledBytesBySession.put(sessionId, agreedTotal);

        meterExitSession(sessionId, connectingUserId, exitSession, deltaBytes);

        Node node = nodeRepository.findById(nodeId).orElse(null);
        User owner = node != null ? node.getOwnerUser() : null;
        if (owner == null) {
            log.warn("P2P session {} on node {} has no owning user — not crediting", sessionId, nodeId);
            return;
        }

        long creditBytes = Math.round(deltaBytes * CREDIT_RATE);
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

        P2pRelayCredit credit = existing.orElseGet(() -> {
            P2pRelayCredit fresh = new P2pRelayCredit();
            fresh.setUser(owner);
            fresh.setSessionId(sessionId);
            fresh.setRelayNode(node);
            fresh.setBytesCredited(0L);
            return fresh;
        });
        credit.setBytesRelayed(agreedTotal);
        long creditedBefore = credit.getBytesCredited() == null ? 0L : credit.getBytesCredited();
        credit.setBytesCredited(creditedBefore + finalCreditBytes);
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

        log.info("P2P session {} credited: user {} relayed {} more bytes (total {}) -> {} bytes credited (node {})",
                sessionId, owner.getId(), deltaBytes, agreedTotal, finalCreditBytes, nodeId);
    }

    /**
     * Charges an *exit* session's bytes to the connecting user's own quota.
     *
     * A relay session needs nothing here: it ends at one of our nodes, and
     * that node already meters the traffic per device the ordinary way
     * (NodeManagementService#processTrafficStats). An exit session touches no
     * node of ours at all, so without this a paid user's P2P browsing would
     * be the one kind of traffic on the platform that costs them nothing —
     * while the peer carrying it still earns credit for every byte.
     *
     * Charged off {@code relayedBytes}, i.e. the *smaller* of the two agreed
     * reports and the same figure the peer is paid on — so the two halves of
     * one session can never disagree about how much traffic it was.
     *
     * How far this is trusted: {@code exitSession} is the connecting client's
     * own word (the peer cannot corroborate it — it is handed an address, and
     * telling us whether that address was one of ours would mean reporting
     * the user's destinations, which this design deliberately never does). A
     * patched client could therefore claim "relay" and browse unmetered. That
     * is the same trust model the credit side already runs on, with the same
     * backstop: nothing is recorded unless both independent reports agree,
     * and the daily cap bounds what any single account can extract.
     */
    private void meterExitSession(String sessionId, Long connectingUserId, boolean exitSession, long relayedBytes) {
        if (!exitSession || connectingUserId == null || relayedBytes <= 0) {
            return;
        }
        subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(connectingUserId, "ACTIVE")
                .ifPresentOrElse(
                        s -> {
                            s.setTrafficUsedBytes(s.getTrafficUsedBytes() + relayedBytes);
                            subscriptionRepository.save(s);
                            log.info("P2P session {}: charged {} exit bytes to user {}", sessionId, relayedBytes, connectingUserId);
                        },
                        () -> log.warn("P2P session {}: user {} used a P2P exit but has no active subscription to charge", sessionId, connectingUserId)
                );
    }
}
