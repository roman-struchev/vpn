package com.vpn.android.vpn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransportFallbackPolicyTest {

    @Test
    public void staysOnXhttpUntilEveryNodeHasBeenTried() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(3, true);
        assertEquals(TransportFallbackPolicy.Transport.XHTTP, policy.getCurrentTransport());

        assertFalse(policy.onNodeSwitch().transportChanged); // node 1->2
        assertFalse(policy.onNodeSwitch().transportChanged); // node 2->3
        assertEquals(TransportFallbackPolicy.Transport.XHTTP, policy.getCurrentTransport());
    }

    @Test
    public void switchesToGrpcAfterExhaustingAllNodesOnXhttp() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(2, true);
        policy.onNodeSwitch(); // 1 of 2
        TransportFallbackPolicy.Outcome outcome = policy.onNodeSwitch(); // 2 of 2 -> exhausted xhttp

        assertTrue(outcome.transportChanged);
        assertFalse(outcome.allTransportsExhausted);
        assertEquals(TransportFallbackPolicy.Transport.GRPC, policy.getCurrentTransport());
    }

    @Test
    public void reportsExhaustionAfterGrpcAlsoFailsOnEveryNode() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(1, true);
        TransportFallbackPolicy.Outcome switchToGrpc = policy.onNodeSwitch();
        assertTrue(switchToGrpc.transportChanged);

        TransportFallbackPolicy.Outcome exhausted = policy.onNodeSwitch();
        assertFalse(exhausted.transportChanged);
        assertTrue(exhausted.allTransportsExhausted);
    }

    @Test
    public void skipsGrpcEntirelyWhenNotAdvertisedByTheServer() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(1, false);
        TransportFallbackPolicy.Outcome outcome = policy.onNodeSwitch();

        assertFalse(outcome.transportChanged);
        assertTrue(outcome.allTransportsExhausted);
        assertEquals(TransportFallbackPolicy.Transport.XHTTP, policy.getCurrentTransport());
    }

    @Test
    public void clampsNodeCountToAtLeastOne() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(0, true);
        // Must not divide-by-zero / loop forever; a single switch should already
        // count as having exhausted the (degenerate, empty) node list.
        assertTrue(policy.onNodeSwitch().transportChanged);
    }
}
