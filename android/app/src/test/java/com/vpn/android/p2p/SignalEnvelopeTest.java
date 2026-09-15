package com.vpn.android.p2p;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The signal envelope (docs/research/P2P_RELAY_FEASIBILITY.md §8.1) is a
 * wire contract shared with independently-built Desktop/Linux relay agents —
 * these tests pin the exact JSON shape so a change here can't silently drift
 * from what those other implementations expect.
 */
public class SignalEnvelopeTest {

    @Test
    public void offerRoundTrips() {
        SignalEnvelope original = SignalEnvelope.offer("v=0...", "203.0.113.7", 443);
        SignalEnvelope parsed = SignalEnvelope.parse(original.toBytes());

        assertEquals(SignalEnvelope.KIND_OFFER, parsed.kind);
        assertEquals("v=0...", parsed.sdp);
        assertEquals("203.0.113.7", parsed.targetHost);
        assertEquals(443, parsed.targetPort);
    }

    @Test
    public void answerRoundTrips() {
        SignalEnvelope original = SignalEnvelope.answer("v=0 answer...");
        SignalEnvelope parsed = SignalEnvelope.parse(original.toBytes());

        assertEquals(SignalEnvelope.KIND_ANSWER, parsed.kind);
        assertEquals("v=0 answer...", parsed.sdp);
    }

    @Test
    public void iceCandidateRoundTrips() {
        SignalEnvelope original = SignalEnvelope.ice("candidate:1 1 UDP ...", "0");
        SignalEnvelope parsed = SignalEnvelope.parse(original.toBytes());

        assertEquals(SignalEnvelope.KIND_ICE, parsed.kind);
        assertEquals("candidate:1 1 UDP ...", parsed.candidate);
        assertEquals("0", parsed.sdpMid);
    }

    @Test
    public void parseReturnsNull_forGarbageBytes() {
        assertNull(SignalEnvelope.parse("not json at all".getBytes()));
    }

    @Test
    public void parseReturnsNull_forJsonMissingKind() {
        assertNull(SignalEnvelope.parse("{\"sdp\":\"v=0\"}".getBytes()));
    }

    @Test
    public void exactWireFormat_offer() {
        // Pinned literal shape — any independently-built peer (Desktop/Linux)
        // must produce/accept exactly this, field names included.
        String json = new String(SignalEnvelope.offer("SDP", "1.2.3.4", 9000).toBytes());
        assertEquals("{\"kind\":\"offer\",\"sdp\":\"SDP\",\"targetHost\":\"1.2.3.4\",\"targetPort\":9000}", json);
    }
}
