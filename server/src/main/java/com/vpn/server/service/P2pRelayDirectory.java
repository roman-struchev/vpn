package com.vpn.server.service;

import com.vpn.server.entity.Node;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Which peers a user may currently connect through, in the two different
 * senses that word now has.
 *
 * The pipe itself is the same either way, and is all a peer ever does: open a
 * TCP connection to the address the connecting client names in its offer and
 * forward opaque bytes (agent's RelaySession / desktop's relayAgent /
 * Android's P2pRelayAgent are a TCP-to-DataChannel bridge and nothing more).
 * What differs is the address the client names:
 *
 * <ul>
 *   <li>{@link #availableRelaysFor} — <b>relay</b>: the address is one of our
 *       own VPN nodes, so the peer is a *path* to it and the VLESS/Reality
 *       session is still end-to-end with that node. Used automatically, as a
 *       last resort when the network blocks dialing the node directly. The
 *       peer's region matters only for latency; the exit stays the node's.</li>
 *   <li>{@link #availableExitsFor} — <b>exit</b>: the addresses are whatever
 *       sites the user is browsing, so the traffic reaches the internet from
 *       the peer's own connection under their IP, with no node of ours in the
 *       path at all (docs §8.9). Chosen deliberately by the user from the
 *       region list, and paid plans only.</li>
 * </ul>
 *
 * Neither list carries a hostname or an IP — see {@link #describe}.
 */
@Service
public class P2pRelayDirectory {

    private final NodeRepository nodeRepository;
    private final SubscriptionRepository subscriptionRepository;

    /** Null in tests that don't care; see P2pReachabilityService. */
    private P2pReachabilityService reachability;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReachability(P2pReachabilityService reachability) {
        this.reachability = reachability;
    }

    /** Not while its reachability check runs, and never if it failed it. */
    private boolean offered(Node node) {
        return reachability == null || reachability.isOffered(node.getId());
    }

    /**
     * Not a peer behind the same carrier NAT as the client under another
     * address: they can't reach each other, and the session would only time
     * out (P2pReachabilityService#sameCarrierNetwork). Only narrows the lists
     * — signaling to such a peer anyway is not refused, it just won't work.
     */
    private boolean reachableFrom(Node node, String clientIp) {
        return reachability == null || !reachability.sameCarrierNetwork(node.getId(), clientIp);
    }

    public P2pRelayDirectory(NodeRepository nodeRepository, SubscriptionRepository subscriptionRepository) {
        this.nodeRepository = nodeRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    /** Whether this user's plan is the free/trial one — the split that decides P2P exit access (see {@link #availableExitsFor}). */
    private boolean isOnTrialPlan(Long userId) {
        Tariff tariff = subscriptionRepository
                .findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .map(Subscription::getEffectiveTariff)
                .orElse(null);
        String pool = (tariff != null && tariff.getServerPool() != null) ? tariff.getServerPool() : "paid";
        return "trial".equalsIgnoreCase(pool);
    }

    /**
     * Peers this user may currently use as an *exit* — the traffic leaves for
     * the internet from that person's own connection, under their IP, rather
     * than from a server we rent (docs/research/P2P_RELAY_FEASIBILITY.md
     * §8.9). This is the list behind a P2P row the user actively picked in the
     * region list, not the blocked-network fallback {@link
     * #availableRelaysFor} serves.
     *
     * Paid plans only. Unlike a VPS region — where the pool flags decide and
     * the answer is per-node — carrying somebody's whole internet session on a
     * volunteer's uplink is the thing a free account does not get, so the
     * gate is the tariff itself and a trial user gets an empty list here
     * (their client shows the row with a padlock, from
     * SubscriptionExportService#getAvailableRegions).
     *
     * {@code region} narrows it to one country; null or blank means every
     * region. Ordered freshest-heartbeat first, so the client works down a
     * list that starts with the peer most likely to still be there.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availableExitsFor(Long userId, String region) {
        return availableExitsFor(userId, region, null);
    }

    /** As above, leaving out peers this client can't reach from its network — see {@link #reachableFrom}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availableExitsFor(Long userId, String region, String clientIp) {
        if (isOnTrialPlan(userId)) {
            return List.of();
        }
        return eligiblePeersFor(userId, clientIp)
                .filter(node -> region == null || region.isBlank() || region.equalsIgnoreCase(node.getRegion()))
                .map(P2pRelayDirectory::describe)
                .toList();
    }

    /**
     * Whether this user may open a session through this peer at all — the
     * check behind the signaling endpoint, which is the only place the rule
     * is actually enforced (the two lists above merely decide what a
     * well-behaved client is shown).
     *
     * Paid plans reach any eligible peer; a trial account reaches only the
     * peers offered to trial, matching {@link #availableRelaysFor}.
     *
     * Known limit, stated rather than papered over: this cannot tell a relay
     * session from an exit one, because the signaling payload is opaque to
     * the server by design and the peer could only corroborate it by
     * reporting the user's destinations, which this design deliberately never
     * does. So a trial account that patches its own client can still use a
     * trial-pool peer as an exit. The paid gate on exits is a product rule
     * enforced at the directory ({@link #availableExitsFor}) and in the
     * clients, not a security boundary — what is enforced here is the pool
     * rule, which previously was not enforced anywhere at all.
     */
    @Transactional(readOnly = true)
    public boolean mayConnectThrough(Long userId, Long nodeId) {
        if (nodeId == null) return false;
        Node node = nodeRepository.findById(nodeId).orElse(null);
        if (node == null) return false;
        // Not a peer at all: signaling to it is meaningless, and this method
        // has nothing to say about ordinary nodes.
        if (!node.isP2p()) return false;
        if (!"ONLINE".equalsIgnoreCase(node.getStatus()) || !node.isEligibleForRelay()) return false;
        if (node.isOwnRelayDeviceOf(userId)) return false;
        if (!offered(node)) return false;
        return !isOnTrialPlan(userId) || Boolean.TRUE.equals(node.getAvailableToTrial());
    }

    /**
     * Relay peers available to this user right now, freshest heartbeat first.
     * Empty is an ordinary answer — relays are other people's devices, and
     * there may simply be none online.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availableRelaysFor(Long userId) {
        return availableRelaysFor(userId, null);
    }

    /** As above, leaving out peers this client can't reach from its network — see {@link #reachableFrom}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availableRelaysFor(Long userId, String clientIp) {
        boolean trial = isOnTrialPlan(userId);

        return eligiblePeersFor(userId, clientIp)
                .filter(node -> trial
                        ? Boolean.TRUE.equals(node.getAvailableToTrial())
                        : Boolean.TRUE.equals(node.getAvailableToPaid()))
                .map(P2pRelayDirectory::describe)
                .toList();
    }

    /**
     * Every peer that may be handed to this user at all, freshest heartbeat
     * first — the part both lists above agree on. What they add on top is the
     * access rule, which differs: a relay follows the node's own pool flags,
     * an exit follows the caller's tariff.
     */
    private java.util.stream.Stream<Node> eligiblePeersFor(Long userId, String clientIp) {
        return nodeRepository.findByTypeAndStatus("p2p", "ONLINE").stream()
                // isEligibleForRelay, not just ONLINE: a TIMED window can lapse
                // between heartbeats, and handing out a relay whose window is
                // over would only produce a session the node refuses anyway
                // (AgentStreamServiceImpl checks it again on every signal).
                .filter(Node::isEligibleForRelay)
                .filter(this::offered)
                .filter(node -> reachableFrom(node, clientIp))
                .filter(node -> !node.isOwnRelayDeviceOf(userId))
                .sorted((a, b) -> {
                    if (a.getLastHeartbeatAt() == null) return 1;
                    if (b.getLastHeartbeatAt() == null) return -1;
                    return b.getLastHeartbeatAt().compareTo(a.getLastHeartbeatAt());
                });
    }

    /**
     * Deliberately no hostname and no IP: a peer is somebody's personal
     * laptop or phone. The client needs an id to signal to and a region to
     * choose by; it reaches the device over WebRTC, never by address.
     */
    private static Map<String, Object> describe(Node node) {
        java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("nodeId", node.getId());
        entry.put("region", node.getRegion());
        entry.put("activeConnections", node.getActiveConnections() == null ? 0 : node.getActiveConnections());
        entry.put("lastSeenAt", node.getLastHeartbeatAt() == null ? null : node.getLastHeartbeatAt().toString());
        return entry;
    }
}
