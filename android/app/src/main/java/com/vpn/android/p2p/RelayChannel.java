package com.vpn.android.p2p;

/**
 * Thin abstraction over "however bytes actually move to/from the connecting
 * peer" — the real implementation ({@code P2pWebRtcSession}) wraps an
 * org.webrtc.DataChannel, but nothing in {@link P2pTcpBridge} needs to know
 * that. Kept as an interface specifically so the byte-forwarding/counting
 * logic (the part actually worth unit-testing) can be tested against a plain
 * fake in a normal JVM test, without ever touching the native WebRTC
 * library — which requires a real Android runtime and, for an actual open
 * connection, a real remote peer neither of which are available in this
 * environment/CI.
 */
public interface RelayChannel {

    void send(byte[] data);

    void close();

    interface Listener {
        void onMessage(byte[] data);

        void onClosed();
    }

    void setListener(Listener listener);
}
