package com.vpn.server.repository;

import com.vpn.server.entity.Node;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long> {
    Optional<Node> findByHostname(String hostname);
    List<Node> findByPoolAndStatus(String pool, String status);
    List<Node> findByPoolInAndStatus(Collection<String> pools, String status);
    List<Node> findByPoolAndRegionAndStatus(String pool, String region, String status);
    List<Node> findByStatus(String status);
    long countByStatus(String status);

    // Tariff-access lookups (docs/research/P2P_RELAY_FEASIBILITY.md §8.3) —
    // independent of the pool/lifecycle queries above.
    List<Node> findByAvailableToTrialTrueAndStatus(String status);
    List<Node> findByAvailableToPaidTrueAndStatus(String status);

    // NodeHealthTask's two jobs (docs §8: p2p nodes churn far more than
    // stable VPS infra, so this repo previously had no mechanism at all for
    // either — a node's status just sat at whatever its last heartbeat set
    // it to, forever). registerNode always sets lastHeartbeatAt at creation
    // time, so there's no real "never heartbeated" (null) case to separately
    // handle — a node that dies right after registering just has an
    // immediately-stale (not null) lastHeartbeatAt, which the first query
    // below already catches.
    List<Node> findByStatusAndLastHeartbeatAtBefore(String status, Instant cutoff);
    List<Node> findByTypeAndStatusAndLastHeartbeatAtBefore(String type, String status, Instant cutoff);
}
