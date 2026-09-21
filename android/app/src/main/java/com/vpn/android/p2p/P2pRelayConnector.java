package com.vpn.android.p2p;

import android.util.Log;

import com.vpn.android.diagnostics.DiagnosticsReporter;

import org.webrtc.PeerConnectionFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes a peer usable by xray: a loopback TCP listener where every accepted
 * connection becomes its own WebRTC session through that person's device.
 *
 * A peer only ever does one thing — open a plain TCP connection to the
 * address we name and pipe opaque bytes — and this listener is used in the
 * two ways that follow from what we name:
 *
 * <ul>
 *   <li><b>Relay</b> (the constructors taking a host and port): every session
 *       goes to that one node of ours, so the peer is a *path* to it when
 *       dialing it directly does not work. The tunnel is still negotiated
 *       end-to-end with the node, so pointing xray's outbound at
 *       127.0.0.1:{@link #getLocalPort()} adds a hop and changes nothing else
 *       (see XrayConfigFactory's dialThrough).</li>
 *   <li><b>Exit</b> ({@link #forExit}): the listener speaks SOCKS5 and each
 *       session goes wherever that connection asked, so the traffic reaches
 *       the internet from the peer's own connection, under their IP, with no
 *       node of ours in the path (see XrayConfigFactory#buildP2pExit).</li>
 * </ul>
 *
 * Signaling goes through the server's broker, which queues what the relay says
 * so the answer and the ICE candidates that follow it are not lost between
 * polls — that queue is what makes a connection through a NAT possible at all.
 */
public class P2pRelayConnector {

    private static final String TAG = "P2pRelayConnector";

    /** How long one session may spend negotiating before it is abandoned. */
    private static final long NEGOTIATION_TIMEOUT_MS = 20_000;
    private static final long POLL_WAIT_MS = 5_000;
    private static final long TRAFFIC_REPORT_INTERVAL_MS = 30_000;

    /**
     * One negotiated session, as the connector uses it. Exists so the accept
     * loop, the byte piping, the negotiation deadline and the traffic
     * reporting — the parts worth being sure about — can be tested on a plain
     * JVM against a fake, without the native WebRTC library, which needs a real
     * Android runtime and a real remote peer.
     */
    public interface ClientSession extends RelayChannel {
        void start();

        void handleAnswer(String answerSdp);

        void handleRemoteIceCandidate(String candidate, String sdpMid);

        void setOnOpen(Runnable callback);
    }

    /** Creates the session for one connection; production makes a {@link P2pClientSession}. */
    public interface SessionFactory {
        ClientSession create(String targetHost, int targetPort, java.util.function.Consumer<SignalEnvelope> outgoingSignal);
    }

    /** What this needs from the API client — narrow on purpose, so tests need no HTTP. */
    public interface Signaling {
        void sendSignal(long relayNodeId, String sessionId, byte[] payload) throws Exception;

        /** The relay's next signal, or null if none arrived within the wait. */
        byte[] pollSignal(String sessionId, long waitMs) throws Exception;

        void closeSession(String sessionId);

        /**
         * @param exit the peer was the exit, not a path to one of our nodes —
         *             which is what makes these bytes count against this
         *             account's own quota server-side (nothing else meters an
         *             exit session).
         */
        void reportTraffic(String sessionId, long relayNodeId, long bytesRelayed, boolean exit);
    }

    private final Signaling signaling;
    private final SessionFactory sessionFactory;
    private final long relayNodeId;
    private final String targetHost;
    private final int targetPort;
    private final long negotiationTimeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<P2pTcpBridge> bridges = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "p2p-relay-connector");
        t.setDaemon(true);
        return t;
    });

    private ServerSocket serverSocket;

    public P2pRelayConnector(Signaling signaling,
                             PeerConnectionFactory peerConnectionFactory,
                             long relayNodeId,
                             String targetHost,
                             int targetPort) {
        this(signaling,
                (host, port, outgoing) -> new P2pClientSession(peerConnectionFactory, host, port, outgoing),
                relayNodeId, targetHost, targetPort, NEGOTIATION_TIMEOUT_MS);
    }

    /**
     * The same listener, used the other way: as a P2P *exit*, where the peer
     * carries the traffic all the way out to the internet instead of to a node
     * of ours (docs §8.9).
     *
     * The difference is only where each session goes, and that is no longer
     * known up front — every connection is to a different site — so the
     * listener speaks SOCKS5 and takes the destination from each request (see
     * {@link Socks5Handshake}). Everything after that is identical: one
     * WebRTC session per connection, the peer dialing what it was named.
     */
    public static P2pRelayConnector forExit(Signaling signaling,
                                            PeerConnectionFactory peerConnectionFactory,
                                            long relayNodeId) {
        return new P2pRelayConnector(signaling,
                (host, port, outgoing) -> new P2pClientSession(peerConnectionFactory, host, port, outgoing),
                relayNodeId, null, 0, NEGOTIATION_TIMEOUT_MS);
    }

    /** Test seam — see {@link SessionFactory}. */
    public P2pRelayConnector(Signaling signaling,
                             SessionFactory sessionFactory,
                             long relayNodeId,
                             String targetHost,
                             int targetPort,
                             long negotiationTimeoutMs) {
        this.signaling = signaling;
        this.sessionFactory = sessionFactory;
        this.relayNodeId = relayNodeId;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.negotiationTimeoutMs = negotiationTimeoutMs;
    }

    /** Starts listening on loopback and returns the port to point the outbound at. */
    public int start() throws IOException {
        // Loopback only: this is a private hop for the local xray, not a proxy
        // offered to whatever network the phone happens to be on.
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        running.set(true);
        executor.submit(this::acceptLoop);
        return serverSocket.getLocalPort();
    }

    public int getLocalPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public int getActiveSessions() {
        return bridges.size();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // already closed
        }
        for (P2pTcpBridge bridge : bridges) {
            bridge.close();
        }
        bridges.clear();
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> openSession(socket));
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "Relay listener stopped accepting", e);
                }
                return;
            }
        }
    }

    private void openSession(Socket socket) {
        String host = targetHost;
        int port = targetPort;
        if (isExit()) {
            try {
                Socks5Handshake.Request request = Socks5Handshake.read(socket);
                host = request.host;
                port = request.port;
            } catch (IOException e) {
                // A local client that cannot speak SOCKS5 to us is our own bug
                // (we point xray at this port), not something to keep alive.
                Log.w(TAG, "Local connection did not complete the SOCKS5 handshake", e);
                closeQuietly(socket);
                return;
            }
        }

        String sessionId = UUID.randomUUID().toString();
        ClientSession session = sessionFactory.create(
                host, port, envelope -> sendSignal(sessionId, envelope));

        AtomicBoolean opened = new AtomicBoolean(false);
        P2pTcpBridge bridge = new P2pTcpBridge(socket, session);
        bridges.add(bridge);
        bridge.onClosed(() -> {
            bridges.remove(bridge);
            signaling.reportTraffic(sessionId, relayNodeId, bridge.getBytesRelayed(), isExit());
            signaling.closeSession(sessionId);
        });

        session.setOnOpen(() -> {
            if (!opened.compareAndSet(false, true)) return;
            if (isExit()) {
                try {
                    Socks5Handshake.writeSuccess(socket);
                } catch (IOException e) {
                    bridge.close();
                    return;
                }
            }
            // Only now may the local socket be read: the peer does not dial
            // the destination until the channel opens, and anything sent
            // before that is dropped on its side.
            bridge.start();
            executor.submit(() -> reportTrafficPeriodically(sessionId, bridge));
        });

        session.start();
        executor.submit(() -> pollLoop(sessionId, session, opened, bridge));
    }

    /**
     * Collects what the relay says until the channel opens. Also the
     * negotiation's deadline: a relay that never answers must not hold a
     * connection attempt open indefinitely, since the caller has other relays
     * to try.
     */
    private void pollLoop(String sessionId, ClientSession session, AtomicBoolean opened, P2pTcpBridge bridge) {
        long deadline = System.currentTimeMillis() + negotiationTimeoutMs;
        while (running.get() && !bridge.isClosed()) {
            if (!opened.get() && System.currentTimeMillis() > deadline) {
                Log.w(TAG, "Relay session " + sessionId + " never opened — giving up on this peer");
                bridge.close();
                return;
            }
            byte[] payload;
            try {
                payload = signaling.pollSignal(sessionId, POLL_WAIT_MS);
            } catch (Exception e) {
                // One failed poll is worth a retry; the deadline above is what
                // actually ends a hopeless session.
                sleep(500);
                continue;
            }
            if (payload == null) {
                if (opened.get()) {
                    // Negotiated and running: nothing more to collect.
                    return;
                }
                continue;
            }
            try {
                SignalEnvelope envelope = SignalEnvelope.parse(payload);
                if ("answer".equals(envelope.kind)) {
                    session.handleAnswer(envelope.sdp);
                } else if ("ice".equals(envelope.kind)) {
                    session.handleRemoteIceCandidate(envelope.candidate, envelope.sdpMid);
                }
            } catch (Exception e) {
                // A malformed signal is the relay's problem; candidates already
                // applied may still be enough to connect.
                Log.d(TAG, "Ignoring an unusable signal in session " + sessionId + ": " + e);
            }
        }
    }

    private void reportTrafficPeriodically(String sessionId, P2pTcpBridge bridge) {
        while (running.get() && !bridge.isClosed()) {
            sleep(TRAFFIC_REPORT_INTERVAL_MS);
            if (bridge.isClosed()) return;
            signaling.reportTraffic(sessionId, relayNodeId, bridge.getBytesRelayed(), isExit());
        }
    }

    /** Whether this listener is an exit hop (SOCKS5, destination per connection) rather than a fixed path to one node. */
    private boolean isExit() {
        return targetHost == null;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
    }

    private void sendSignal(String sessionId, SignalEnvelope envelope) {
        executor.submit(() -> {
            try {
                signaling.sendSignal(relayNodeId, sessionId, envelope.toBytes());
            } catch (Exception e) {
                // An offer that cannot be delivered means this relay is gone —
                // reported so a relay that is listed but never reachable is
                // visible rather than showing up only as "it doesn't connect".
                DiagnosticsReporter.warn("p2p-relay", "RELAY_SIGNAL_FAILED",
                        "Could not deliver a signaling payload to relay node " + relayNodeId);
            }
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
