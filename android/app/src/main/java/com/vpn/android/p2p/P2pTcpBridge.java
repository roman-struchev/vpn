package com.vpn.android.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges one relay session's {@link RelayChannel} (a WebRTC DataChannel in
 * production) with a raw TCP socket to the destination the connecting client
 * asked for (docs/research/P2P_RELAY_FEASIBILITY.md §8.1). This device never
 * parses the bytes flowing through here — they're the connecting client's
 * own VLESS/Reality traffic to a real egress node, opaque to us by design
 * (see the class doc on P2pRelayAgent for why no local Xray-core is needed
 * on this side at all).
 *
 * Byte counting here (not on the WebRTC side) is what gets self-reported via
 * P2pSessionTrafficReport — see P2pRelayAgent#reportTrafficPeriodically.
 */
public class P2pTcpBridge {

    private final Socket socket;
    private final RelayChannel channel;
    private final AtomicLong bytesRelayed = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Runnable onClosed;
    private Thread readerThread;

    /**
     * @param targetAddress    the exact, already-DNS-resolved address to
     *                         connect to (see DestinationAcl#resolveAllowedAddress)
     *                         — deliberately not a hostname: connecting via a
     *                         pre-resolved InetAddress means this constructor
     *                         performs no DNS lookup of its own, so it cannot
     *                         land on a different (possibly private/blocked)
     *                         address than the one the ACL check just approved.
     * @param connectTimeoutMs bounded so a slow/blackholed destination cannot
     *                         pin a thread indefinitely — matched by the ACL
     *                         check already having run before this is ever
     *                         constructed (see P2pRelayAgent.connectAndBridge).
     */
    public P2pTcpBridge(InetAddress targetAddress, int targetPort, RelayChannel channel, int connectTimeoutMs) throws IOException {
        this.channel = channel;
        this.socket = new Socket();
        socket.connect(new InetSocketAddress(targetAddress, targetPort), connectTimeoutMs);
        channel.setListener(new RelayChannel.Listener() {
            @Override
            public void onMessage(byte[] data) {
                writeToSocket(data);
            }

            @Override
            public void onClosed() {
                close();
            }
        });
    }

    /**
     * The mirror case: an already-connected socket, used by the *connecting*
     * side (P2pRelayConnector), where the socket is the local one xray dialed
     * and the channel leads out to the relay. Identical piping and counting —
     * only the direction of the story differs, so it would be a mistake to
     * write it twice.
     */
    public P2pTcpBridge(Socket connectedSocket, RelayChannel channel) {
        this.channel = channel;
        this.socket = connectedSocket;
        channel.setListener(new RelayChannel.Listener() {
            @Override
            public void onMessage(byte[] data) {
                writeToSocket(data);
            }

            @Override
            public void onClosed() {
                close();
            }
        });
    }

    /** Starts the socket-to-channel direction; channel-to-socket is driven by {@link RelayChannel.Listener#onMessage}. */
    public void start() {
        readerThread = new Thread(this::pumpSocketToChannel, "p2p-tcp-bridge-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void pumpSocketToChannel() {
        try {
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while (!closed.get() && (n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                // Count before dispatch, not after — a caller polling
                // getBytesRelayed() to know "has this chunk been delivered
                // yet" must never observe the delivery before the count.
                bytesRelayed.addAndGet(n);
                channel.send(chunk);
            }
        } catch (IOException ignored) {
            // Destination closed/reset — normal end-of-session, not an error to surface.
        } finally {
            close();
        }
    }

    private void writeToSocket(byte[] data) {
        if (closed.get()) return;
        try {
            bytesRelayed.addAndGet(data.length);
            OutputStream out = socket.getOutputStream();
            out.write(data);
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    public long getBytesRelayed() {
        return bytesRelayed.get();
    }

    public void onClosed(Runnable callback) {
        this.onClosed = callback;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        channel.close();
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (onClosed != null) {
            onClosed.run();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
