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
 * Makes a relay peer usable by xray: a loopback TCP listener where every
 * accepted connection becomes its own relayed session to the node.
 *
 * A relay is not an exit and not a region — it is a way to reach a node when
 * dialing it directly does not work. It opens a plain TCP connection to the
 * address we name and pipes opaque bytes; the tunnel itself is still
 * negotiated end-to-end with the node, so pointing xray's outbound at
 * 127.0.0.1:{@link #getLocalPort()} adds a hop without changing anything else
 * about the connection (see XrayConfigFactory's dialThrough).
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

        void reportTraffic(String sessionId, long relayNodeId, long bytesRelayed);
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
        String sessionId = UUID.randomUUID().toString();
        ClientSession session = sessionFactory.create(
                targetHost, targetPort, envelope -> sendSignal(sessionId, envelope));

        AtomicBoolean opened = new AtomicBoolean(false);
        P2pTcpBridge bridge = new P2pTcpBridge(socket, session);
        bridges.add(bridge);
        bridge.onClosed(() -> {
            bridges.remove(bridge);
            signaling.reportTraffic(sessionId, relayNodeId, bridge.getBytesRelayed());
            signaling.closeSession(sessionId);
        });

        session.setOnOpen(() -> {
            if (!opened.compareAndSet(false, true)) return;
            // Only now may the local socket be read: the relay does not dial
            // the node until the channel opens, and anything sent before that
            // is dropped on its side.
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
            signaling.reportTraffic(sessionId, relayNodeId, bridge.getBytesRelayed());
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
