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
 * Which relay peers a user may currently connect *through*.
 *
 * A relay is not an exit node and not a region: it forwards opaque bytes to a
 * VPN node the connecting client names in its own offer (agent's RelaySession
 * / desktop's relayAgent are a TCP-to-DataChannel pipe and nothing more). So
 * this list answers "what paths do I have to reach a node when dialing it
 * directly does not work", which is the entire purpose of the feature — and
 * why relays are deliberately absent from the region list a user picks an
 * exit from (SubscriptionExportService#getAvailableRegions).
 *
 * The relay's own region is reported because it decides the path's latency,
 * not the exit country; a client picks the nearest one it can reach.
 */
@Service
public class P2pRelayDirectory {

    private final NodeRepository nodeRepository;
    private final SubscriptionRepository subscriptionRepository;

    public P2pRelayDirectory(NodeRepository nodeRepository, SubscriptionRepository subscriptionRepository) {
        this.nodeRepository = nodeRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Relay peers available to this user right now, freshest heartbeat first.
     * Empty is an ordinary answer — relays are other people's devices, and
     * there may simply be none online.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availableRelaysFor(Long userId) {
        Tariff tariff = subscriptionRepository
                .findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .map(Subscription::getEffectiveTariff)
                .orElse(null);
        String pool = (tariff != null && tariff.getServerPool() != null) ? tariff.getServerPool() : "paid";
        boolean trial = "trial".equalsIgnoreCase(pool);

        return nodeRepository.findByTypeAndStatus("p2p", "ONLINE").stream()
                // isEligibleForRelay, not just ONLINE: a TIMED window can lapse
                // between heartbeats, and handing out a relay whose window is
                // over would only produce a session the node refuses anyway
                // (AgentStreamServiceImpl checks it again on every signal).
                .filter(Node::isEligibleForRelay)
                .filter(node -> !node.isOwnRelayDeviceOf(userId))
                .filter(node -> trial
                        ? Boolean.TRUE.equals(node.getAvailableToTrial())
                        : Boolean.TRUE.equals(node.getAvailableToPaid()))
                .sorted((a, b) -> {
                    if (a.getLastHeartbeatAt() == null) return 1;
                    if (b.getLastHeartbeatAt() == null) return -1;
                    return b.getLastHeartbeatAt().compareTo(a.getLastHeartbeatAt());
                })
                .map(node -> {
                    // Deliberately no hostname and no IP: a relay is somebody's
                    // personal laptop or phone. The client needs an id to
                    // signal to and a region to choose by; it reaches the
                    // device over WebRTC, never by address.
                    java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("nodeId", node.getId());
                    entry.put("region", node.getRegion());
                    entry.put("activeConnections", node.getActiveConnections() == null ? 0 : node.getActiveConnections());
                    entry.put("lastSeenAt", node.getLastHeartbeatAt() == null ? null : node.getLastHeartbeatAt().toString());
                    return entry;
                })
                .toList();
    }
}
