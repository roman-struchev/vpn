package com.vpn.android.p2p;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * SOCKS5 (RFC 1928), only as much of it as the local end of a P2P exit hop
 * needs.
 *
 * Why it exists: when a peer is used as an *exit* rather than as a path to one
 * of our nodes, every connection goes somewhere different — whatever site the
 * user opened — so the destination cannot be fixed when the bridge starts, the
 * way it is for a relay session. SOCKS5 is how xray hands us that destination
 * per connection (docs/research/P2P_RELAY_FEASIBILITY.md §8.9).
 *
 * Deliberately not implemented: authentication (this listener is on loopback
 * and is reached only by the xray we start ourselves), BIND, and UDP
 * ASSOCIATE — a peer forwards a TCP stream and nothing else, which is why the
 * exit config blocks UDP and answers DNS over DoH instead.
 *
 * Reads with {@link DataInputStream#readFully}, which takes exactly the bytes
 * asked for: anything the client sends after the request stays in the socket
 * for {@link P2pTcpBridge} to pick up, rather than being swallowed here.
 */
public final class Socks5Handshake {

    private Socks5Handshake() {
    }

    private static final byte VERSION = 0x05;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6 = 0x04;
    private static final byte NO_AUTH = 0x00;

    private static final byte REPLY_SUCCEEDED = 0x00;
    private static final byte REPLY_COMMAND_NOT_SUPPORTED = 0x07;
    private static final byte REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

    /** Where one accepted connection wants to go. */
    public static final class Request {
        public final String host;
        public final int port;

        Request(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Reads the greeting and the CONNECT request, answering the greeting.
     * Throws if the caller is not speaking SOCKS5 to us, which would be a bug
     * on our own side — we point xray at this port ourselves.
     */
    public static Request read(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();

        if (in.readByte() != VERSION) {
            throw new IOException("socks: unsupported version in greeting");
        }
        int methodCount = in.readUnsignedByte();
        in.readFully(new byte[methodCount]); // the offered methods, none of which we need to look at
        out.write(new byte[]{VERSION, NO_AUTH});
        out.flush();

        if (in.readByte() != VERSION) {
            throw new IOException("socks: unsupported version in request");
        }
        byte command = in.readByte();
        in.readByte(); // reserved
        byte addressType = in.readByte();

        if (command != CMD_CONNECT) {
            // Answered rather than dropped, so the local client fails cleanly
            // instead of waiting on a reply that never comes.
            writeReply(out, REPLY_COMMAND_NOT_SUPPORTED);
            throw new IOException("socks: unsupported command " + command);
        }

        String host;
        switch (addressType) {
            case ATYP_IPV4: {
                byte[] raw = new byte[4];
                in.readFully(raw);
                host = (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + "." + (raw[3] & 0xFF);
                break;
            }
            case ATYP_DOMAIN: {
                byte[] raw = new byte[in.readUnsignedByte()];
                in.readFully(raw);
                host = new String(raw, StandardCharsets.UTF_8);
                break;
            }
            case ATYP_IPV6: {
                byte[] raw = new byte[16];
                in.readFully(raw);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i += 2) {
                    if (i > 0) sb.append(':');
                    sb.append(Integer.toHexString(((raw[i] & 0xFF) << 8) | (raw[i + 1] & 0xFF)));
                }
                host = sb.toString();
                break;
            }
            default:
                writeReply(out, REPLY_ADDRESS_TYPE_NOT_SUPPORTED);
                throw new IOException("socks: unsupported address type " + addressType);
        }

        int port = in.readUnsignedShort();
        return new Request(host, port);
    }

    /**
     * "Succeeded", with 0.0.0.0:0 as the bound address — the real one is the
     * peer's and we never learn it, nor does the local client need it.
     *
     * Sent only once the session is actually carrying bytes. An earlier reply
     * would have xray believe it has a working connection while the
     * negotiation is still running, turning an unreachable peer into a hung
     * request instead of a quick move to the next one.
     */
    public static void writeSuccess(Socket socket) throws IOException {
        writeReply(socket.getOutputStream(), REPLY_SUCCEEDED);
    }

    private static void writeReply(OutputStream out, byte code) throws IOException {
        out.write(new byte[]{VERSION, code, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0});
        out.flush();
    }
}
