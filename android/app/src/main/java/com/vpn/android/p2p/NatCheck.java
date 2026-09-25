package com.vpn.android.p2p;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether this network can take incoming P2P sessions at all — the same test
 * as the desktop app's natCheck.ts.
 *
 * Relaying works by both sides learning their public address from a STUN
 * server and sending to each other's (STUN only, no TURN). That needs a NAT
 * that keeps one public port per local socket whatever the destination. A
 * symmetric NAT — most mobile carriers — hands out a new port per
 * destination, so the address STUN reports is useless to the peer. Seen
 * live: this app relaying over LTE was listed as a peer and nobody could
 * connect through it; over Wi-Fi it worked.
 *
 * One socket asks two different STUN servers what they see: two different
 * answers mean symmetric, no answer means UDP is blocked, and one answer
 * can't tell — that is let through rather than refused on a guess.
 */
public final class NatCheck {

    public enum Verdict { OK, SYMMETRIC, NO_UDP, UNKNOWN }

    /** Different operators on purpose: a symmetric NAT is told apart by destination. */
    public static final List<InetSocketAddress> DEFAULT_SERVERS = List.of(
            InetSocketAddress.createUnresolved("stun.l.google.com", 19302),
            InetSocketAddress.createUnresolved("stun.cloudflare.com", 3478));

    private static volatile List<InetSocketAddress> serversOverride;

    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final SecureRandom RANDOM = new SecureRandom();

    private NatCheck() {
    }

    /** The servers the app checks against: the defaults, unless a test swapped them. */
    public static List<InetSocketAddress> servers() {
        List<InetSocketAddress> override = serversOverride;
        return override != null ? override : DEFAULT_SERVERS;
    }

    /** Test seam: point the app's checks at fake STUN servers; null restores the defaults. */
    @androidx.annotation.VisibleForTesting
    public static void overrideServersForTests(List<InetSocketAddress> servers) {
        serversOverride = servers;
    }

    public static boolean refuses(Verdict verdict) {
        return verdict == Verdict.SYMMETRIC || verdict == Verdict.NO_UDP;
    }

    /** Blocking, up to timeoutMs; never on the main thread. */
    public static Verdict check(List<InetSocketAddress> servers, int timeoutMs) {
        try (DatagramSocket socket = new DatagramSocket()) {
            Map<String, byte[]> pending = new HashMap<>();
            for (InetSocketAddress server : servers) {
                byte[] txId = new byte[12];
                RANDOM.nextBytes(txId);
                try {
                    InetAddress address = InetAddress.getByName(server.getHostString());
                    byte[] request = bindingRequest(txId);
                    socket.send(new DatagramPacket(request, request.length, address, server.getPort()));
                    pending.put(Arrays.toString(txId), txId);
                } catch (Exception e) {
                    // Unresolvable or unsendable: counts as no answer from it.
                }
            }

            Set<String> seen = new HashSet<>();
            int answered = 0;
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (!pending.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                socket.setSoTimeout((int) left);
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                }
                byte[] msg = Arrays.copyOf(packet.getData(), packet.getLength());
                if (msg.length < 20) continue;
                String key = Arrays.toString(Arrays.copyOfRange(msg, 8, 20));
                if (pending.remove(key) == null) continue;
                String mapped = parseMappedAddress(msg);
                if (mapped != null) {
                    answered++;
                    seen.add(mapped);
                }
            }

            if (answered == 0) return Verdict.NO_UDP;
            if (answered == 1) return Verdict.UNKNOWN;
            return seen.size() == 1 ? Verdict.OK : Verdict.SYMMETRIC;
        } catch (Exception e) {
            return Verdict.UNKNOWN;
        }
    }

    static byte[] bindingRequest(byte[] txId) {
        byte[] request = new byte[20];
        request[0] = 0x00;
        request[1] = 0x01; // Binding Request
        request[4] = (byte) (MAGIC_COOKIE >>> 24);
        request[5] = (byte) (MAGIC_COOKIE >>> 16);
        request[6] = (byte) (MAGIC_COOKIE >>> 8);
        request[7] = (byte) MAGIC_COOKIE;
        System.arraycopy(txId, 0, request, 8, 12);
        return request;
    }

    /** "ip:port" from XOR-MAPPED-ADDRESS (or the legacy MAPPED-ADDRESS), IPv4 only. */
    static String parseMappedAddress(byte[] msg) {
        int offset = 20;
        String legacy = null;
        while (offset + 4 <= msg.length) {
            int type = ((msg[offset] & 0xff) << 8) | (msg[offset + 1] & 0xff);
            int length = ((msg[offset + 2] & 0xff) << 8) | (msg[offset + 3] & 0xff);
            int v = offset + 4;
            if (length >= 8 && v + 8 <= msg.length && (msg[v + 1] & 0xff) == 0x01) {
                int rawPort = ((msg[v + 2] & 0xff) << 8) | (msg[v + 3] & 0xff);
                if (type == 0x0020) {
                    int port = rawPort ^ (MAGIC_COOKIE >>> 16);
                    return ((msg[v + 4] & 0xff) ^ 0x21) + "." + ((msg[v + 5] & 0xff) ^ 0x12) + "."
                            + ((msg[v + 6] & 0xff) ^ 0xa4) + "." + ((msg[v + 7] & 0xff) ^ 0x42) + ":" + port;
                }
                if (type == 0x0001) {
                    legacy = (msg[v + 4] & 0xff) + "." + (msg[v + 5] & 0xff) + "."
                            + (msg[v + 6] & 0xff) + "." + (msg[v + 7] & 0xff) + ":" + rawPort;
                }
            }
            offset += 4 + length + ((4 - (length % 4)) % 4);
        }
        return legacy;
    }
}
