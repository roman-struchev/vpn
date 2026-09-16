package com.vpn.server.task;

import com.vpn.server.entity.Node;
import com.vpn.server.repository.NodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Two related lifecycle jobs neither had any prior mechanism for (docs/
 * research/P2P_RELAY_FEASIBILITY.md §8 — the repo owner's own observation:
 * "p2p ноды могут динамически быстро появляться и пропадать, в этом случае
 * пусть они пропадают и из админки"):
 *
 * 1. STALE-ONLINE DETECTION (all node types): a node's `status` was
 *    previously only ever set by its own heartbeat/registration handler —
 *    nothing ever flipped it back to OFFLINE if heartbeats simply stopped
 *    arriving (a crashed VPS agent, a p2p relay client closing the laptop
 *    lid, an app force-quit). Any node still marked ONLINE whose last
 *    heartbeat is older than STALE_ONLINE_THRESHOLD_SECONDS gets flipped to
 *    OFFLINE here.
 *
 * 2. P2P NODE PRUNING (type=p2p only): a VPS/direct/cdn node staying listed
 *    while OFFLINE is exactly what an operator needs (something to go fix);
 *    a p2p node is somebody's laptop or phone that may simply never come
 *    back (uninstalled the app, one-time tester, decommissioned device) —
 *    keeping every one of those rows forever would make the admin node list
 *    unboundedly cluttered with dead entries. Once a p2p node has been
 *    OFFLINE for P2P_PRUNE_AFTER_HOURS, it's deleted outright — safe for its
 *    earned-credit history, since p2p_relay_credits.relay_node_id is
 *    ON DELETE SET NULL (see V11 migration), not a hard foreign-key
 *    dependency the delete would be blocked by.
 */
@Component
public class NodeHealthTask {

    private static final Logger log = LoggerFactory.getLogger(NodeHealthTask.class);

    // ~3x the default heartbeat interval (vpn.heartbeat-interval-sec, 30s) —
    // the same "stale after ~3 missed intervals" convention the admin UI's
    // own recentBytesPerSec staleness check already uses client-side
    // (NodesSection.tsx's STATS_STALE_AFTER_MS).
    private static final long STALE_ONLINE_THRESHOLD_SECONDS = 90;

    // Generous enough to survive a laptop sleeping overnight or a flaky
    // connection, short enough to keep the p2p node list meaningfully live.
    private static final long P2P_PRUNE_AFTER_HOURS = 24;

    private final NodeRepository nodeRepository;

    public NodeHealthTask(NodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 15000)
    @Transactional
    public void run() {
        markStaleOnlineNodesOffline();
        pruneLongDeadP2pNodes();
    }

    private void markStaleOnlineNodesOffline() {
        Instant staleCutoff = Instant.now().minus(STALE_ONLINE_THRESHOLD_SECONDS, ChronoUnit.SECONDS);
        List<Node> staleOnline = nodeRepository.findByStatusAndLastHeartbeatAtBefore("ONLINE", staleCutoff);
        if (staleOnline.isEmpty()) {
            return;
        }
        for (Node node : staleOnline) {
            node.setStatus("OFFLINE");
        }
        nodeRepository.saveAll(staleOnline);
        log.info("Marked {} stale node(s) OFFLINE (no heartbeat in >{}s)", staleOnline.size(), STALE_ONLINE_THRESHOLD_SECONDS);
    }

    private void pruneLongDeadP2pNodes() {
        Instant pruneCutoff = Instant.now().minus(P2P_PRUNE_AFTER_HOURS, ChronoUnit.HOURS);
        List<Node> longDead = nodeRepository.findByTypeAndStatusAndLastHeartbeatAtBefore("p2p", "OFFLINE", pruneCutoff);
        if (longDead.isEmpty()) {
            return;
        }
        nodeRepository.deleteAll(longDead);
        log.info("Pruned {} p2p node(s) offline for over {}h", longDead.size(), P2P_PRUNE_AFTER_HOURS);
    }
}
