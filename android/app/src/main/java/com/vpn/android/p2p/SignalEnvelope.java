package com.vpn.android.p2p;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;

/**
 * Wire format for the opaque bytes carried inside P2pSignal.payload (docs/
 * research/P2P_RELAY_FEASIBILITY.md §8.1). The server never parses this —
 * it's a contract purely between a connecting client and a relay agent, so
 * it must match byte-for-byte across every platform's independent
 * implementation (Desktop/Linux forks are being built in parallel against
 * this same envelope). JSON, UTF-8, one of three "kind" values:
 *
 * <pre>
 * offer  (client -> relay node, always the FIRST message of a session):
 *   {"kind":"offer","sdp":"...","targetHost":"...","targetPort":1234}
 * answer (relay node -> client):
 *   {"kind":"answer","sdp":"..."}
 * ice    (either direction, trickled as candidates are discovered):
 *   {"kind":"ice","candidate":"...","sdpMid":"..."}
 * </pre>
 *
 * A relay node is always the WebRTC *answerer* — it never initiates a
 * session, only ever reacts to an incoming "offer".
 */
public final class SignalEnvelope {

    public static final String KIND_OFFER = "offer";
    public static final String KIND_ANSWER = "answer";
    public static final String KIND_ICE = "ice";

    private static final Gson GSON = new Gson();

    public String kind;
    public String sdp;
    public String targetHost;
    public int targetPort;
    public String candidate;
    public String sdpMid;

    public static SignalEnvelope offer(String sdp, String targetHost, int targetPort) {
        SignalEnvelope e = new SignalEnvelope();
        e.kind = KIND_OFFER;
        e.sdp = sdp;
        e.targetHost = targetHost;
        e.targetPort = targetPort;
        return e;
    }

    public static SignalEnvelope answer(String sdp) {
        SignalEnvelope e = new SignalEnvelope();
        e.kind = KIND_ANSWER;
        e.sdp = sdp;
        return e;
    }

    public static SignalEnvelope ice(String candidate, String sdpMid) {
        SignalEnvelope e = new SignalEnvelope();
        e.kind = KIND_ICE;
        e.candidate = candidate;
        e.sdpMid = sdpMid;
        return e;
    }

    public byte[] toBytes() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    /** @return the parsed envelope, or null if {@code bytes} isn't a well-formed one — callers must treat that as "drop this signal", never throw across the gRPC callback boundary. */
    public static SignalEnvelope parse(byte[] bytes) {
        try {
            SignalEnvelope e = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), SignalEnvelope.class);
            if (e == null || e.kind == null) {
                return null;
            }
            return e;
        } catch (JsonSyntaxException ex) {
            return null;
        }
    }
}
