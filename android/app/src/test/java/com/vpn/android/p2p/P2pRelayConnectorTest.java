package com.vpn.android.p2p;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The connector's job: turn a relay peer into a local port xray can dial.
 * Everything here runs on a plain JVM — real sockets, a fake session standing
 * in for WebRTC (which needs a device and a remote peer) — so the parts that
 * actually decide whether a user gets a connection are pinned down: the offer
 * naming the right node, bytes crossing both ways, the negotiation deadline,
 * and the traffic report that pays the relay's owner.
 */
public class P2pRelayConnectorTest {

    private P2pRelayConnector connector;

    @After
    public void tearDown() {
        if (connector != null) {
            connector.stop();
        }
    }

    /** Records what the client sends and hands back whatever the test queues for it. */
    private static final class FakeSignaling implements P2pRelayConnector.Signaling {
        final List<SignalEnvelope> sent = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<byte[]> inbound = new ConcurrentLinkedQueue<>();
        final List<Long> trafficReports = new CopyOnWriteArrayList<>();
        final List<Boolean> trafficReportExitFlags = new CopyOnWriteArrayList<>();
        final List<String> closedSessions = new CopyOnWriteArrayList<>();
        volatile boolean failSend = false;

        @Override
        public void sendSignal(long relayNodeId, String sessionId, byte[] payload) throws Exception {
            if (failSend) throw new Exception("relay unreachable");
            sent.add(SignalEnvelope.parse(payload));
        }

        @Override
        public byte[] pollSignal(String sessionId, long waitMs) {
            long deadline = System.currentTimeMillis() + Math.min(waitMs, 200);
            while (System.currentTimeMillis() < deadline) {
                byte[] next = inbound.poll();
                if (next != null) return next;
                sleep(10);
            }
            return null;
        }

        @Override
        public void closeSession(String sessionId) {
            closedSessions.add(sessionId);
        }

        @Override
        public void reportTraffic(String sessionId, long relayNodeId, long bytesRelayed, boolean exit) {
            trafficReports.add(bytesRelayed);
            trafficReportExitFlags.add(exit);
        }
    }

    /** Stands in for the WebRTC session: records what it was asked to send, and can be "opened" on demand. */
    private static final class FakeSession implements P2pRelayConnector.ClientSession {
        final List<byte[]> sentToRelay = new CopyOnWriteArrayList<>();
        final List<String> answers = new CopyOnWriteArrayList<>();
        final List<String> candidates = new CopyOnWriteArrayList<>();
        private final Consumer<SignalEnvelope> outgoing;
        private final String targetHost;
        private final int targetPort;
        private volatile Listener listener;
        private volatile Runnable onOpen;
        volatile boolean closed = false;

        FakeSession(String targetHost, int targetPort, Consumer<SignalEnvelope> outgoing) {
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.outgoing = outgoing;
        }

        @Override
        public void start() {
            outgoing.accept(SignalEnvelope.offer("fake-offer-sdp", targetHost, targetPort));
        }

        @Override
        public void handleAnswer(String answerSdp) {
            answers.add(answerSdp);
        }

        @Override
        public void handleRemoteIceCandidate(String candidate, String sdpMid) {
            candidates.add(candidate);
        }

        @Override
        public void setOnOpen(Runnable callback) {
            this.onOpen = callback;
        }

        @Override
        public void send(byte[] data) {
            sentToRelay.add(data);
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        /** What the relay would do: the channel opens, and later bytes come back. */
        void open() {
            Runnable callback = onOpen;
            if (callback != null) callback.run();
        }

        void deliverFromRelay(byte[] data) {
            Listener current = listener;
            if (current != null) current.onMessage(data);
        }
    }

    private static final class Harness {
        final FakeSignaling signaling = new FakeSignaling();
        final List<FakeSession> sessions = new CopyOnWriteArrayList<>();

        P2pRelayConnector connector(long negotiationTimeoutMs) {
            return new P2pRelayConnector(
                    signaling,
                    (host, port, outgoing) -> {
                        FakeSession session = new FakeSession(host, port, outgoing);
                        sessions.add(session);
                        return session;
                    },
                    42L, "node.example.com", 443, negotiationTimeoutMs);
        }
    }

    @Test
    public void offersTheNodeItWasAskedToReachAndCarriesBytesBothWays() throws Exception {
        Harness harness = new Harness();
        connector = harness.connector(5_000);
        int port = connector.start();

        try (Socket local = new Socket("127.0.0.1", port)) {
            FakeSession session = awaitSession(harness);

            // The offer names the node — a relay never chooses the destination.
            SignalEnvelope offer = awaitFirstSent(harness.signaling);
            assertEquals("offer", offer.kind);
            assertEquals("node.example.com", offer.targetHost);
            assertEquals(443, offer.targetPort);

            session.open();

            OutputStream out = local.getOutputStream();
            out.write("client-hello".getBytes());
            out.flush();
            awaitTrue(() -> !session.sentToRelay.isEmpty(), 3000);
            assertEquals("client-hello", new String(session.sentToRelay.get(0)));

            session.deliverFromRelay("node-hello".getBytes());
            InputStream in = local.getInputStream();
            byte[] buf = new byte[64];
            int n = in.read(buf);
            assertEquals("node-hello", new String(buf, 0, n));
        }
    }

    @Test
    public void nothingIsReadFromTheSocketBeforeTheRelayCanReceiveIt() throws Exception {
        // The relay only dials the node once the channel opens; bytes sent
        // earlier are dropped on its side, which would silently corrupt the
        // very first thing the tunnel says.
        Harness harness = new Harness();
        connector = harness.connector(5_000);
        int port = connector.start();

        try (Socket local = new Socket("127.0.0.1", port)) {
            FakeSession session = awaitSession(harness);
            local.getOutputStream().write("too-early".getBytes());
            local.getOutputStream().flush();
            sleep(300);

            assertTrue("nothing may be forwarded before the channel opens", session.sentToRelay.isEmpty());

            session.open();
            awaitTrue(() -> !session.sentToRelay.isEmpty(), 3000);
            assertEquals("too-early", new String(session.sentToRelay.get(0)));
        }
    }

    @Test
    public void feedsTheRelaysAnswerAndCandidatesIntoTheSession() throws Exception {
        Harness harness = new Harness();
        connector = harness.connector(5_000);
        int port = connector.start();

        try (Socket local = new Socket("127.0.0.1", port)) {
            FakeSession session = awaitSession(harness);
            harness.signaling.inbound.add(SignalEnvelope.answer("fake-answer-sdp").toBytes());
            harness.signaling.inbound.add(SignalEnvelope.ice("candidate:1 udp", "0").toBytes());

            awaitTrue(() -> !session.answers.isEmpty() && !session.candidates.isEmpty(), 5000);
            assertEquals("fake-answer-sdp", session.answers.get(0));
            assertEquals("candidate:1 udp", session.candidates.get(0));
        }
    }

    @Test
    public void abandonsASessionTheRelayNeverOpens() throws Exception {
        // A peer that answers nothing must not hold a connection attempt open:
        // the caller has other relays to try, and the user is waiting.
        Harness harness = new Harness();
        connector = harness.connector(600);
        int port = connector.start();

        try (Socket local = new Socket("127.0.0.1", port)) {
            awaitSession(harness);
            awaitTrue(() -> connector.getActiveSessions() == 0, 6000);
            assertFalse(harness.signaling.closedSessions.isEmpty());
        }
    }

    @Test
    public void reportsWhatItRelayedWhenTheConnectionEnds() throws Exception {
        Harness harness = new Harness();
        connector = harness.connector(5_000);
        int port = connector.start();

        Socket local = new Socket("127.0.0.1", port);
        FakeSession session = awaitSession(harness);
        session.open();
        local.getOutputStream().write("0123456789".getBytes());
        local.getOutputStream().flush();
        awaitTrue(() -> !session.sentToRelay.isEmpty(), 3000);
        local.close();

        awaitTrue(() -> !harness.signaling.trafficReports.isEmpty(), 5000);
        assertTrue("the relay's owner is paid from these reports",
                harness.signaling.trafficReports.get(harness.signaling.trafficReports.size() - 1) >= 10);
    }

    @Test
    public void stoppingClosesEverythingItStillHolds() throws Exception {
        Harness harness = new Harness();
        connector = harness.connector(5_000);
        int port = connector.start();

        try (Socket local = new Socket("127.0.0.1", port)) {
            FakeSession session = awaitSession(harness);
            session.open();
            awaitTrue(() -> connector.getActiveSessions() == 1, 3000);

            connector.stop();

            assertEquals(0, connector.getActiveSessions());
            assertTrue(session.closed);
        }
    }

    // ---- Exit mode: the peer is the exit, not a path to one of our nodes ----

    /** The same harness, as an exit hop: no fixed destination, SOCKS5 per connection. */
    private static P2pRelayConnector exitConnector(Harness harness, long negotiationTimeoutMs) {
        return new P2pRelayConnector(
                harness.signaling,
                (host, port, outgoing) -> {
                    FakeSession session = new FakeSession(host, port, outgoing);
                    harness.sessions.add(session);
                    return session;
                },
                42L, null, 0, negotiationTimeoutMs);
    }

    /** Greeting + CONNECT for a domain destination, which is what xray's socks outbound sends. */
    private static void writeSocks5Connect(Socket socket, String host, int port) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write(new byte[]{0x05, 0x01, 0x00});
        out.flush();

        byte[] greetingReply = new byte[2];
        readFully(socket.getInputStream(), greetingReply);
        assertArrayEquals(new byte[]{0x05, 0x00}, greetingReply);

        byte[] hostBytes = host.getBytes();
        OutputStream req = socket.getOutputStream();
        req.write(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) hostBytes.length});
        req.write(hostBytes);
        req.write(new byte[]{(byte) (port >> 8), (byte) port});
        req.flush();
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) fail("stream ended after " + read + " of " + buf.length + " bytes");
            read += n;
        }
    }

    @Test
    public void takesTheDestinationFromEachSocks5RequestSoOnePeerServesEverySite() throws Exception {
        Harness harness = new Harness();
        connector = exitConnector(harness, 5_000);
        int port = connector.start();

        try (Socket local = new Socket("127.0.0.1", port)) {
            writeSocks5Connect(local, "example.com", 443);

            FakeSession session = awaitSession(harness);
            // The offer names where THIS connection asked to go — not one
            // address fixed when the listener started, which is the whole
            // difference between an exit and a relay.
            SignalEnvelope offer = awaitFirstSent(harness.signaling);
            assertEquals("offer", offer.kind);
            assertEquals("example.com", offer.targetHost);
            assertEquals(443, offer.targetPort);

            session.open();

            // The success reply only comes once the session is open: an
            // earlier one would have xray believe in a connection that may
            // still fail, turning an unreachable peer into a hung request.
            byte[] reply = new byte[10];
            readFully(local.getInputStream(), reply);
            assertEquals(0x05, reply[0]);
            assertEquals("succeeded", 0x00, reply[1]);

            local.getOutputStream().write("GET / HTTP/1.1".getBytes());
            local.getOutputStream().flush();
            awaitTrue(() -> !session.sentToRelay.isEmpty(), 3000);
            assertEquals("GET / HTTP/1.1", new String(session.sentToRelay.get(0)));

            session.deliverFromRelay("HTTP/1.1 200 OK".getBytes());
            byte[] buf = new byte[64];
            int n = local.getInputStream().read(buf);
            assertEquals("HTTP/1.1 200 OK", new String(buf, 0, n));
        }
    }

    @Test
    public void reportsAnExitSessionAsOneSoTheBytesReachTheCallersOwnQuota() throws Exception {
        Harness harness = new Harness();
        connector = exitConnector(harness, 5_000);
        int port = connector.start();

        Socket local = new Socket("127.0.0.1", port);
        writeSocks5Connect(local, "example.com", 80);
        FakeSession session = awaitSession(harness);
        session.open();
        readFully(local.getInputStream(), new byte[10]);
        local.getOutputStream().write("0123456789".getBytes());
        local.getOutputStream().flush();
        awaitTrue(() -> !session.sentToRelay.isEmpty(), 3000);
        local.close();

        awaitTrue(() -> !harness.signaling.trafficReportExitFlags.isEmpty(), 5000);
        assertTrue("nothing else meters an exit session — no node of ours was in the path",
                harness.signaling.trafficReportExitFlags.get(harness.signaling.trafficReportExitFlags.size() - 1));
    }

    @Test
    public void dropsALocalClientThatIsNotSpeakingSocks5() throws Exception {
        Harness harness = new Harness();
        connector = exitConnector(harness, 500);
        int port = connector.start();

        try (Socket local = new Socket("127.0.0.1", port)) {
            local.getOutputStream().write(new byte[]{0x04, 0x01}); // SOCKS4, which we deliberately do not speak
            local.getOutputStream().flush();

            // No destination was ever established, so no session and nothing signalled.
            sleep(300);
            assertTrue(harness.sessions.isEmpty());
            assertTrue(harness.signaling.sent.isEmpty());
            assertEquals(0, connector.getActiveSessions());
        }
    }

    private static FakeSession awaitSession(Harness harness) {
        awaitTrue(() -> !harness.sessions.isEmpty(), 3000);
        return harness.sessions.get(0);
    }

    private static SignalEnvelope awaitFirstSent(FakeSignaling signaling) {
        awaitTrue(() -> !signaling.sent.isEmpty(), 3000);
        return signaling.sent.get(0);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            sleep(20);
        }
        fail("condition was not met within " + timeoutMs + "ms");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
