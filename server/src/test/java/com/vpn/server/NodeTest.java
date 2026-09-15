package com.vpn.server;

import com.vpn.server.entity.Node;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Node#isEligibleForRelay — the server-side gate that decides whether
 * a p2p node is still handed out to a connecting client (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.5), independent of whatever the relaying
 * client itself currently thinks its own state is.
 */
class NodeTest {

    private Node p2pNode() {
        Node n = new Node();
        n.setType("p2p");
        return n;
    }

    @Test
    void nonP2pNode_alwaysEligible_regardlessOfRelayMode() {
        Node n = new Node();
        n.setType("direct");
        n.setRelayMode("OFF");
        assertTrue(n.isEligibleForRelay());
    }

    @Test
    void p2pNode_offMode_notEligible() {
        Node n = p2pNode();
        n.setRelayMode("OFF");
        assertFalse(n.isEligibleForRelay());
    }

    @Test
    void p2pNode_alwaysMode_eligible() {
        Node n = p2pNode();
        n.setRelayMode("ALWAYS");
        assertTrue(n.isEligibleForRelay());
    }

    @Test
    void p2pNode_timedMode_eligibleWhileWindowStillOpen() {
        Node n = p2pNode();
        n.setRelayMode("TIMED");
        n.setRelayExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        assertTrue(n.isEligibleForRelay());
    }

    @Test
    void p2pNode_timedMode_notEligibleOnceWindowExpires() {
        // The whole point of server-side enforcement (docs §8.5): the node
        // must stop being handed out the moment its window passes, even if
        // the relaying client is still (incorrectly, or maliciously) telling
        // itself it's still active.
        Node n = p2pNode();
        n.setRelayMode("TIMED");
        n.setRelayExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        assertFalse(n.isEligibleForRelay());
    }

    @Test
    void p2pNode_timedMode_notEligibleWithNoExpiryOnFile() {
        Node n = p2pNode();
        n.setRelayMode("TIMED");
        n.setRelayExpiresAt(null);
        assertFalse(n.isEligibleForRelay());
    }

    @Test
    void isP2p_caseInsensitive() {
        Node n = new Node();
        n.setType("P2P");
        assertTrue(n.isP2p());
    }
}
