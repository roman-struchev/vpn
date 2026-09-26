package com.vpn.server.p2p;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Can somebody on the internet actually reach this relaying device? Asked the
 * way a real client would: an offer over the ordinary signaling path, then
 * ICE connectivity checks from here to the addresses the device itself
 * advertised in its answer. A signed answer to one of those checks means a
 * client gets through; none within the time means nobody will.
 *
 * Only the device's own advertised candidates count, never an address merely
 * seen on an incoming packet (a peer-reflexive candidate): a device behind a
 * symmetric NAT — a mobile carrier — reaches out to us from a port it
 * advertised to nobody, so a public server could talk back to it while no
 * client behind a NAT of its own ever could. Seen live: a phone relaying over
 * LTE was listed as an exit, and every session through it timed out.
 *
 * Stops at ICE: no DTLS, no data channel, nothing is relayed. The offer names
 * a harmless target (our own server), since the Android relay dials the
 * target as soon as an offer arrives.
 */
public class ReachabilityProbe {

    public enum Verdict {
        /** A check to an address the device advertised was answered. */
        REACHABLE,
        /** The device answered the offer, but none of its addresses could be reached. */
        UNREACHABLE,
        /** No verdict: the device never answered the offer, or our own side could not start. */
        INCONCLUSIVE
    }

    public record Result(Verdict verdict, String detail) {
    }

    /** The signaling path to one device: the same envelopes a client exchanges with a relay. */
    public interface Signaling {
        void send(byte[] envelope) throws Exception;

        /** The device's next envelope, or null if none arrived within the wait. */
        byte[] poll(long waitMs) throws Exception;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final InetSocketAddress stunServer;
    private final String targetHost;
    private final int targetPort;
    private final long timeoutMs;
    /** Test seam: loopback "devices" are private addresses, which a real probe skips. */
    private boolean allowPrivateCandidates;

    ReachabilityProbe allowPrivateCandidatesForTests() {
        this.allowPrivateCandidates = true;
        return this;
    }

    public ReachabilityProbe(InetSocketAddress stunServer, String targetHost, int targetPort, long timeoutMs) {
        this.stunServer = stunServer;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.timeoutMs = timeoutMs;
    }

    /** From an ephemeral port, our address learned from the STUN server — for a host with no NAT of its own. */
    public Result run(Signaling signaling) {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetSocketAddress ours = discoverOwnAddress(socket);
            if (ours == null) {
                return new Result(Verdict.INCONCLUSIVE, "could not learn our own public address from " + stunServer);
            }
            return run(signaling, socket, ours);
        } catch (Exception e) {
            return new Result(Verdict.INCONCLUSIVE, "probe failed on our side: " + e);
        }
    }

    /**
     * From a socket whose port is published 1:1 on a known public address —
     * the server in Docker. Its NAT hands every new outgoing flow a random
     * port, so a learned address would be wrong for the device; a published
     * port is what the device's packets reach, and our checks then travel
     * back inside the flow the device itself opened.
     */
    public Result run(Signaling signaling, DatagramSocket socket, InetSocketAddress advertised) {
        try {
            InetSocketAddress ours = advertised;

            String ufrag = random(8);
            String password = random(24);
            signaling.send(offer(ufrag, password, ours));

            Remote remote = new Remote();
            Thread signals = new Thread(() -> readSignals(signaling, remote), "p2p-probe-signals");
            signals.setDaemon(true);
            signals.start();
            try {
                return checkUntilAnswered(socket, remote, ufrag, password);
            } finally {
                signals.interrupt();
            }
        } catch (Exception e) {
            return new Result(Verdict.INCONCLUSIVE, "probe failed on our side: " + e);
        }
    }

    /** What the device told us: its ICE credentials and the addresses it advertised. */
    private static final class Remote {
        final AtomicReference<String> ufrag = new AtomicReference<>();
        final AtomicReference<String> password = new AtomicReference<>();
        final List<InetSocketAddress> candidates = new CopyOnWriteArrayList<>();
        volatile boolean answered;
    }

    private void readSignals(Signaling signaling, Remote remote) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!Thread.currentThread().isInterrupted() && System.currentTimeMillis() < deadline) {
            try {
                byte[] envelope = signaling.poll(Math.max(1, Math.min(2000, deadline - System.currentTimeMillis())));
                if (envelope == null) continue;
                JsonNode node = JSON.readTree(new String(envelope, StandardCharsets.UTF_8));
                String kind = node.path("kind").asText();
                if ("answer".equals(kind)) {
                    for (String line : node.path("sdp").asText().split("\\r?\\n")) {
                        if (line.startsWith("a=ice-ufrag:")) remote.ufrag.set(line.substring(12).trim());
                        else if (line.startsWith("a=ice-pwd:")) remote.password.set(line.substring(10).trim());
                        else if (line.startsWith("a=candidate:")) addCandidate(remote, line.substring(2), allowPrivateCandidates);
                    }
                    remote.answered = true;
                } else if ("ice".equals(kind)) {
                    addCandidate(remote, node.path("candidate").asText(), allowPrivateCandidates);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // one unreadable envelope is not a verdict
            }
        }
    }

    private Result checkUntilAnswered(DatagramSocket socket, Remote remote, String ufrag, String password) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long tieBreaker = RANDOM.nextLong();
        Map<String, InetSocketAddress> pending = new HashMap<>();
        byte[] buf = new byte[1500];
        long nextRound = 0;
        while (System.currentTimeMillis() < deadline) {
            if (remote.answered && System.currentTimeMillis() >= nextRound && remote.password.get() != null) {
                for (InetSocketAddress candidate : remote.candidates) {
                    byte[] txId = IceStun.newTransactionId();
                    pending.put(Arrays.toString(txId), candidate);
                    byte[] check = IceStun.connectivityCheck(txId, remote.ufrag.get() + ":" + ufrag, remote.password.get(), tieBreaker);
                    socket.send(new DatagramPacket(check, check.length, candidate));
                }
                nextRound = System.currentTimeMillis() + 200;
            }
            socket.setSoTimeout(50);
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            }
            int type = IceStun.type(packet.getData(), packet.getLength());
            if (type == IceStun.BINDING_REQUEST) {
                // Answering the device's own checks is what lets it keep its
                // NAT open towards us — as a real client's ICE agent would.
                byte[] reply = IceStun.bindingSuccess(IceStun.transactionId(packet.getData()),
                        (InetSocketAddress) packet.getSocketAddress(), password);
                socket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
            } else if (type == IceStun.BINDING_SUCCESS) {
                InetSocketAddress target = pending.get(Arrays.toString(IceStun.transactionId(packet.getData())));
                String remotePassword = remote.password.get();
                if (target != null && remotePassword != null
                        && IceStun.hasValidIntegrity(packet.getData(), packet.getLength(), remotePassword)) {
                    return new Result(Verdict.REACHABLE, "answered on " + target);
                }
            }
        }
        if (!remote.answered) {
            return new Result(Verdict.INCONCLUSIVE, "the device did not answer the offer");
        }
        return new Result(Verdict.UNREACHABLE, "no answer from any advertised address " + remote.candidates);
    }

    /** Public IPv4 UDP candidates only: a private address is unreachable from here by definition. */
    private static void addCandidate(Remote remote, String candidate, boolean allowPrivate) {
        // candidate:<foundation> <component> <transport> <priority> <ip> <port> typ <type> ...
        String[] t = candidate.trim().replaceFirst("^a=", "").split("\\s+");
        if (t.length < 8 || !"udp".equalsIgnoreCase(t[2])) return;
        try {
            InetAddress ip = InetAddress.getByName(t[4]);
            if (!(ip instanceof Inet4Address) || !(allowPrivate || isPublic(ip))) return;
            InetSocketAddress address = new InetSocketAddress(ip, Integer.parseInt(t[5]));
            if (!remote.candidates.contains(address)) remote.candidates.add(address);
        } catch (Exception ignored) {
            // not a literal address (an mDNS name): nothing to check
        }
    }

    static boolean isPublic(InetAddress ip) {
        byte[] a = ip.getAddress();
        boolean cgnat = (a[0] & 0xff) == 100 && (a[1] & 0xc0) == 64;
        return !(ip.isSiteLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isAnyLocalAddress() || ip.isMulticastAddress() || cgnat);
    }

    private InetSocketAddress discoverOwnAddress(DatagramSocket socket) throws Exception {
        byte[] txId = IceStun.newTransactionId();
        byte[] request = IceStun.plainBindingRequest(txId);
        InetSocketAddress server = new InetSocketAddress(stunServer.getHostString(), stunServer.getPort());
        byte[] buf = new byte[512];
        for (int attempt = 0; attempt < 3; attempt++) {
            socket.send(new DatagramPacket(request, request.length, server));
            socket.setSoTimeout(1000);
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                if (Arrays.equals(IceStun.transactionId(packet.getData()), txId)) {
                    return IceStun.mappedAddress(packet.getData(), packet.getLength());
                }
            } catch (SocketTimeoutException ignored) {
                // retry
            }
        }
        return null;
    }

    private byte[] offer(String ufrag, String password, InetSocketAddress ours) throws Exception {
        String ip = ours.getAddress().getHostAddress();
        String sdp = String.join("\r\n",
                "v=0",
                "o=- " + Math.abs(RANDOM.nextLong()) + " 2 IN IP4 127.0.0.1",
                "s=-",
                "t=0 0",
                "a=group:BUNDLE 0",
                "a=msid-semantic: WMS",
                "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
                "c=IN IP4 0.0.0.0",
                "a=ice-ufrag:" + ufrag,
                "a=ice-pwd:" + password,
                "a=ice-options:trickle",
                "a=fingerprint:sha-256 " + fingerprint(),
                "a=setup:actpass",
                "a=mid:0",
                "a=sctp-port:5000",
                "a=max-message-size:262144",
                "a=candidate:1 1 udp 1686052607 " + ip + " " + ours.getPort() + " typ srflx raddr 0.0.0.0 rport 0",
                "") ;
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("kind", "offer");
        envelope.put("sdp", sdp);
        envelope.put("targetHost", targetHost);
        envelope.put("targetPort", targetPort);
        return JSON.writeValueAsBytes(envelope);
    }

    /** Well-formed but never used: the probe stops before DTLS. */
    private static String fingerprint() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", b[i]));
        }
        return sb.toString();
    }

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        return sb.toString();
    }
}
