package com.vpn.android.p2p;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Validates the plumbing P2pTcpBridge is actually responsible for
 * (bidirectional forwarding, byte counting, close propagation) against a
 * real loopback TCP server and a fake {@link RelayChannel} standing in for
 * a WebRTC DataChannel — no real WebRTC/NAT traversal needed to test this
 * part, which is the entire point of RelayChannel being an interface (see
 * its class doc).
 */
public class P2pTcpBridgeTest {

    /** A RelayChannel that records what was sent to it and lets the test inject inbound messages/close. */
    private static class FakeRelayChannel implements RelayChannel {
        final List<byte[]> sent = new ArrayList<>();
        Listener listener;
        volatile boolean closed = false;

        @Override
        public void send(byte[] data) {
            sent.add(data);
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }
    }

    @Test
    public void forwardsSocketBytes_toChannel() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            CountDownLatch accepted = new CountDownLatch(1);
            final Socket[] serverSide = new Socket[1];
            Thread acceptor = new Thread(() -> {
                try {
                    serverSide[0] = server.accept();
                    accepted.countDown();
                } catch (IOException ignored) {
                }
            });
            acceptor.start();

            FakeRelayChannel channel = new FakeRelayChannel();
            P2pTcpBridge bridge = new P2pTcpBridge(InetAddress.getByName("127.0.0.1"), port, channel, 2000);
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            bridge.start();

            byte[] payload = "hello-from-destination".getBytes(StandardCharsets.UTF_8);
            serverSide[0].getOutputStream().write(payload);
            serverSide[0].getOutputStream().flush();

            // The bridge's reader thread is async — poll briefly rather than sleep-and-hope.
            long deadline = System.currentTimeMillis() + 2000;
            while (channel.sent.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            assertEquals(1, channel.sent.size());
            assertArrayEquals(payload, channel.sent.get(0));
            assertEquals(payload.length, bridge.getBytesRelayed());

            bridge.close();
            serverSide[0].close();
        }
    }

    @Test
    public void forwardsChannelBytes_toSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            CountDownLatch accepted = new CountDownLatch(1);
            final Socket[] serverSide = new Socket[1];
            Thread acceptor = new Thread(() -> {
                try {
                    serverSide[0] = server.accept();
                    accepted.countDown();
                } catch (IOException ignored) {
                }
            });
            acceptor.start();

            FakeRelayChannel channel = new FakeRelayChannel();
            P2pTcpBridge bridge = new P2pTcpBridge(InetAddress.getByName("127.0.0.1"), port, channel, 2000);
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            bridge.start();

            byte[] payload = "hello-from-client".getBytes(StandardCharsets.UTF_8);
            channel.listener.onMessage(payload);

            byte[] buf = new byte[64];
            serverSide[0].setSoTimeout(2000);
            int n = serverSide[0].getInputStream().read(buf);

            assertEquals("hello-from-client", new String(buf, 0, n, StandardCharsets.UTF_8));
            assertEquals(payload.length, bridge.getBytesRelayed());

            bridge.close();
            serverSide[0].close();
        }
    }

    @Test
    public void closingChannel_closesSocketAndBridge() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread acceptor = new Thread(() -> {
                try {
                    server.accept();
                } catch (IOException ignored) {
                }
            });
            acceptor.start();

            FakeRelayChannel channel = new FakeRelayChannel();
            P2pTcpBridge bridge = new P2pTcpBridge(InetAddress.getByName("127.0.0.1"), port, channel, 2000);
            bridge.start();

            channel.listener.onClosed();

            assertTrue(bridge.isClosed());
        }
    }

    @Test
    public void onClosedCallback_firesExactlyOnce() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread acceptor = new Thread(() -> {
                try {
                    server.accept();
                } catch (IOException ignored) {
                }
            });
            acceptor.start();

            FakeRelayChannel channel = new FakeRelayChannel();
            P2pTcpBridge bridge = new P2pTcpBridge(InetAddress.getByName("127.0.0.1"), port, channel, 2000);
            java.util.concurrent.atomic.AtomicInteger closedCount = new java.util.concurrent.atomic.AtomicInteger();
            bridge.onClosed(closedCount::incrementAndGet);

            bridge.close();
            bridge.close(); // idempotent — must not double-fire

            assertEquals(1, closedCount.get());
        }
    }

    @Test(expected = IOException.class)
    public void constructorThrows_whenDestinationUnreachable() throws Exception {
        // Port 1 on loopback: nothing listens there, and the OS refuses the
        // connection near-instantly rather than needing the full timeout.
        new P2pTcpBridge(InetAddress.getByName("127.0.0.1"), 1, new FakeRelayChannel(), 500);
    }
}
