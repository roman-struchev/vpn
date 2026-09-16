package com.vpn.server;

import com.vpn.server.entity.Node;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.task.NodeHealthTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers NodeHealthTask's two jobs — stale-ONLINE detection (no prior
 * mechanism existed for this at all, for any node type) and p2p-node
 * pruning (docs/research/P2P_RELAY_FEASIBILITY.md §8 — p2p nodes churn far
 * more than stable VPS infra, and the repo owner explicitly asked for
 * long-dead ones to actually disappear from the admin panel).
 */
@ExtendWith(MockitoExtension.class)
class NodeHealthTaskTest {

    @Mock
    private NodeRepository nodeRepository;

    private NodeHealthTask task;

    @BeforeEach
    void setUp() {
        task = new NodeHealthTask(nodeRepository);
    }

    private Node node(long id, String type, String status) {
        Node n = new Node();
        n.setId(id);
        n.setType(type);
        n.setStatus(status);
        return n;
    }

    @Test
    void marksStaleOnlineNodesOffline_regardlessOfType() {
        Node staleDirect = node(1L, "direct", "ONLINE");
        when(nodeRepository.findByStatusAndLastHeartbeatAtBefore(eq("ONLINE"), any(Instant.class)))
                .thenReturn(List.of(staleDirect));
        when(nodeRepository.findByTypeAndStatusAndLastHeartbeatAtBefore(eq("p2p"), eq("OFFLINE"), any(Instant.class)))
                .thenReturn(List.of());

        task.run();

        assertEquals("OFFLINE", staleDirect.getStatus());
        ArgumentCaptor<List<Node>> captor = ArgumentCaptor.forClass(List.class);
        verify(nodeRepository).saveAll(captor.capture());
        assertTrue(captor.getValue().contains(staleDirect));
    }

    @Test
    void doesNotTouchRepository_whenNothingIsStale() {
        when(nodeRepository.findByStatusAndLastHeartbeatAtBefore(eq("ONLINE"), any(Instant.class)))
                .thenReturn(List.of());
        when(nodeRepository.findByTypeAndStatusAndLastHeartbeatAtBefore(eq("p2p"), eq("OFFLINE"), any(Instant.class)))
                .thenReturn(List.of());

        task.run();

        verify(nodeRepository, never()).saveAll(any());
        verify(nodeRepository, never()).deleteAll(anyList());
    }

    @Test
    void prunesLongOfflineP2pNodes() {
        Node deadP2p = node(2L, "p2p", "OFFLINE");
        when(nodeRepository.findByStatusAndLastHeartbeatAtBefore(eq("ONLINE"), any(Instant.class)))
                .thenReturn(List.of());
        when(nodeRepository.findByTypeAndStatusAndLastHeartbeatAtBefore(eq("p2p"), eq("OFFLINE"), any(Instant.class)))
                .thenReturn(List.of(deadP2p));

        task.run();

        ArgumentCaptor<List<Node>> captor = ArgumentCaptor.forClass(List.class);
        verify(nodeRepository).deleteAll(captor.capture());
        assertTrue(captor.getValue().contains(deadP2p));
    }

    @Test
    void neverPrunes_directOrCdnNodes_evenIfLongOffline() {
        // The repository query itself is scoped to type="p2p" — this test
        // guards the intent (VPS infra must stay listed while OFFLINE for an
        // operator to go fix), not just the query signature.
        when(nodeRepository.findByStatusAndLastHeartbeatAtBefore(eq("ONLINE"), any(Instant.class)))
                .thenReturn(List.of());
        when(nodeRepository.findByTypeAndStatusAndLastHeartbeatAtBefore(eq("p2p"), eq("OFFLINE"), any(Instant.class)))
                .thenReturn(List.of());

        task.run();

        verify(nodeRepository, never()).deleteAll(anyList());
        verify(nodeRepository, never()).findByTypeAndStatusAndLastHeartbeatAtBefore(eq("direct"), anyString(), any());
        verify(nodeRepository, never()).findByTypeAndStatusAndLastHeartbeatAtBefore(eq("cdn"), anyString(), any());
    }
}
