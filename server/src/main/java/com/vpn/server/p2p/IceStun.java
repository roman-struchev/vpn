package com.vpn.server.p2p;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * The few STUN messages an ICE connectivity check needs (RFC 8445 §7.2,
 * RFC 5389): Binding requests and responses with USERNAME,
 * MESSAGE-INTEGRITY, FINGERPRINT, PRIORITY and ICE-CONTROLLING. Just enough
 * for {@link ReachabilityProbe} — deliberately not a general STUN stack.
 */
final class IceStun {

    static final int BINDING_REQUEST = 0x0001;
    static final int BINDING_SUCCESS = 0x0101;
    private static final int MAGIC_COOKIE = 0x2112A442;

    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_USERNAME = 0x0006;
    private static final int ATTR_MESSAGE_INTEGRITY = 0x0008;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final int ATTR_PRIORITY = 0x0024;
    private static final int ATTR_USE_CANDIDATE = 0x0025;
    private static final int ATTR_FINGERPRINT = 0x8028;
    private static final int ATTR_ICE_CONTROLLING = 0x802A;

    private static final SecureRandom RANDOM = new SecureRandom();

    private IceStun() {
    }

    static byte[] newTransactionId() {
        byte[] id = new byte[12];
        RANDOM.nextBytes(id);
        return id;
    }

    /** A plain Binding request, as sent to a public STUN server to learn our own mapped address. */
    static byte[] plainBindingRequest(byte[] txId) {
        return finish(BINDING_REQUEST, txId, new ByteArrayOutputStream(), null, false);
    }

    /** An ICE connectivity check towards the peer: signed with the peer's password. */
    static byte[] connectivityCheck(byte[] txId, String username, String peerPassword, long tieBreaker) {
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        attribute(attrs, ATTR_USERNAME, username.getBytes(StandardCharsets.UTF_8));
        attribute(attrs, ATTR_PRIORITY, ByteBuffer.allocate(4).putInt(0x6E0001FF).array());
        attribute(attrs, ATTR_ICE_CONTROLLING, ByteBuffer.allocate(8).putLong(tieBreaker).array());
        attribute(attrs, ATTR_USE_CANDIDATE, new byte[0]);
        return finish(BINDING_REQUEST, txId, attrs, peerPassword, true);
    }

    /** Our answer to the peer's own check: where we saw it from, signed with our password. */
    static byte[] bindingSuccess(byte[] txId, InetSocketAddress seenFrom, String ourPassword) {
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        attribute(attrs, ATTR_XOR_MAPPED_ADDRESS, xorAddress(seenFrom));
        return finish(BINDING_SUCCESS, txId, attrs, ourPassword, true);
    }

    static int type(byte[] msg, int length) {
        if (length < 20 || ByteBuffer.wrap(msg, 4, 4).getInt() != MAGIC_COOKIE) return -1;
        return ((msg[0] & 0xff) << 8) | (msg[1] & 0xff);
    }

    static byte[] transactionId(byte[] msg) {
        return Arrays.copyOfRange(msg, 8, 20);
    }

    /** The mapped address a STUN server reported, IPv4 only. */
    static InetSocketAddress mappedAddress(byte[] msg, int length) {
        int offset = 20;
        InetSocketAddress legacy = null;
        while (offset + 4 <= length) {
            int type = ((msg[offset] & 0xff) << 8) | (msg[offset + 1] & 0xff);
            int len = ((msg[offset + 2] & 0xff) << 8) | (msg[offset + 3] & 0xff);
            int v = offset + 4;
            if (len >= 8 && v + 8 <= length && (msg[v + 1] & 0xff) == 0x01) {
                int port = ((msg[v + 2] & 0xff) << 8) | (msg[v + 3] & 0xff);
                byte[] ip = Arrays.copyOfRange(msg, v + 4, v + 8);
                try {
                    if (type == ATTR_XOR_MAPPED_ADDRESS) {
                        port ^= MAGIC_COOKIE >>> 16;
                        byte[] cookie = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array();
                        for (int i = 0; i < 4; i++) ip[i] ^= cookie[i];
                        return new InetSocketAddress(InetAddress.getByAddress(ip), port);
                    }
                    if (type == ATTR_MAPPED_ADDRESS) {
                        legacy = new InetSocketAddress(InetAddress.getByAddress(ip), port);
                    }
                } catch (Exception ignored) {
                    return null;
                }
            }
            offset += 4 + len + ((4 - (len % 4)) % 4);
        }
        return legacy;
    }

    /**
     * Whether a response carries a valid MESSAGE-INTEGRITY for this password
     * — proof it came from the device we negotiated with, not from whatever
     * happens to answer on that address.
     */
    static boolean hasValidIntegrity(byte[] msg, int length, String password) {
        int offset = 20;
        while (offset + 4 <= length) {
            int type = ((msg[offset] & 0xff) << 8) | (msg[offset + 1] & 0xff);
            int len = ((msg[offset + 2] & 0xff) << 8) | (msg[offset + 3] & 0xff);
            if (type == ATTR_MESSAGE_INTEGRITY && len == 20 && offset + 24 <= length) {
                byte[] signed = Arrays.copyOf(msg, offset);
                // The header's length covers everything up to and including MESSAGE-INTEGRITY.
                int adjusted = offset + 24 - 20;
                signed[2] = (byte) (adjusted >>> 8);
                signed[3] = (byte) adjusted;
                byte[] expected = hmacSha1(password, signed);
                return java.security.MessageDigest.isEqual(expected, Arrays.copyOfRange(msg, offset + 4, offset + 24));
            }
            offset += 4 + len + ((4 - (len % 4)) % 4);
        }
        return false;
    }

    private static void attribute(ByteArrayOutputStream out, int type, byte[] value) {
        out.write(type >>> 8);
        out.write(type);
        out.write(value.length >>> 8);
        out.write(value.length);
        out.writeBytes(value);
        for (int i = value.length; i % 4 != 0; i++) out.write(0);
    }

    private static byte[] finish(int type, byte[] txId, ByteArrayOutputStream attrs, String integrityKey, boolean fingerprint) {
        byte[] body = attrs.toByteArray();
        if (integrityKey != null) {
            byte[] head = header(type, body.length + 24, txId);
            byte[] signed = concat(head, body);
            ByteArrayOutputStream withMi = new ByteArrayOutputStream();
            withMi.writeBytes(body);
            attribute(withMi, ATTR_MESSAGE_INTEGRITY, hmacSha1(integrityKey, signed));
            body = withMi.toByteArray();
        }
        if (fingerprint) {
            byte[] head = header(type, body.length + 8, txId);
            CRC32 crc = new CRC32();
            crc.update(concat(head, body));
            ByteArrayOutputStream withFp = new ByteArrayOutputStream();
            withFp.writeBytes(body);
            attribute(withFp, ATTR_FINGERPRINT, ByteBuffer.allocate(4).putInt((int) (crc.getValue() ^ 0x5354554EL)).array());
            body = withFp.toByteArray();
        }
        return concat(header(type, body.length, txId), body);
    }

    private static byte[] header(int type, int length, byte[] txId) {
        return ByteBuffer.allocate(20).putShort((short) type).putShort((short) length).putInt(MAGIC_COOKIE).put(txId).array();
    }

    private static byte[] xorAddress(InetSocketAddress address) {
        byte[] ip = address.getAddress().getAddress();
        ByteBuffer b = ByteBuffer.allocate(8);
        b.put((byte) 0).put((byte) 0x01).putShort((short) (address.getPort() ^ (MAGIC_COOKIE >>> 16)));
        byte[] cookie = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array();
        for (int i = 0; i < 4; i++) b.put((byte) (ip[i] ^ cookie[i]));
        return b.array();
    }

    private static byte[] hmacSha1(String key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
