package com.vpn.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.android.api.model.RelayInfo;
import com.vpn.android.p2p.P2pRelayConnector;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.webrtc.PeerConnectionFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * This app as a P2P exit client against a real relay — in practice the
 * desktop app's RelayAgent — through a real server: the path the phone takes
 * when a P2P region is picked. Needs a paid account and an online relay in
 * the given region, passed as instrumentation arguments:
 * p2pEmail, p2pPassword, p2pRegion. Skips without them.
 *
 * Mirrors XrayVpnService: the connector's own listener, then one SOCKS5
 * connection per request (the connector negotiates a WebRTC session per
 * connection), timed — the service's tunnel check gives up after
 * 2 x LIVENESS_PROBE_TIMEOUT_MS.
 */
@RunWith(AndroidJUnit4.class)
public class P2pExitFlowTest {

    private static final String TAG = "P2pExitFlowTest";

    @Test
    public void aRequestGoesOutThroughAPeer() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String email = args.getString("p2pEmail");
        String password = args.getString("p2pPassword");
        String region = args.getString("p2pRegion");
        Assume.assumeTrue("needs p2pEmail/p2pPassword/p2pRegion", email != null && password != null && region != null);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ApiClient api = new ApiClient(new TokenStore(context));
        api.login(email, password);

        List<RelayInfo> exits = api.getP2pExits(region);
        Log.i(TAG, "exits in " + region + ": " + exits.size());
        assertFalse("no P2P exit peer offered in " + region, exits.isEmpty());

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions());
        PeerConnectionFactory factory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        P2pRelayConnector connector = P2pRelayConnector.forExit(new P2pRelayConnector.Signaling() {
            @Override public void sendSignal(long nodeId, String sessionId, byte[] payload) throws Exception {
                long t = System.currentTimeMillis();
                api.sendP2pSignal(nodeId, sessionId, payload);
                Log.i(TAG, "sendSignal " + payload.length + "B took " + (System.currentTimeMillis() - t) + "ms");
            }
            @Override public byte[] pollSignal(String sessionId, long waitMs) throws Exception {
                byte[] b = api.pollP2pSignal(sessionId, waitMs);
                Log.i(TAG, "pollSignal -> " + (b == null ? "nothing" : b.length + "B"));
                return b;
            }
            @Override public void closeSession(String sessionId) { api.closeP2pSession(sessionId); }
            @Override public void reportTraffic(String s, long n, long b, boolean exit) { api.reportP2pSessionTraffic(s, n, b, exit); }
        }, factory, pick(exits, args.getString("p2pNodeId")));

        int port = connector.start();
        try {
            for (int i = 1; i <= 3; i++) {
                long t0 = System.currentTimeMillis();
                String body = getThroughSocks5(port, "example.com", 30_000);
                long took = System.currentTimeMillis() - t0;
                Log.i(TAG, "request " + i + " through the peer: " + took + "ms, got " + body.length() + " chars");
                assertTrue("request " + i + " did not come back through the peer", body.contains("Example Domain"));
                assertTrue("request " + i + " took " + took + "ms, the service's tunnel check waits 8000ms", took < 8000);
            }
        } finally {
            connector.stop();
        }
    }

    /** A given peer (p2pNodeId) when several are in the region, else the freshest one. */
    private static long pick(List<RelayInfo> exits, String nodeId) {
        if (nodeId == null) return exits.get(0).nodeId;
        for (RelayInfo e : exits) {
            if (String.valueOf(e.nodeId).equals(nodeId)) return e.nodeId;
        }
        throw new AssertionError("peer " + nodeId + " is not among the offered exits");
    }

    /** One SOCKS5 CONNECT + plain HTTP GET, as xray's socks outbound would do. */
    private static String getThroughSocks5(int port, String host, int timeoutMs) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(new byte[]{5, 1, 0});
            readFully(in, 2);
            byte[] h = host.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[]{5, 1, 0, 3, (byte) h.length});
            req.write(h);
            req.write(new byte[]{0, 80});
            out.write(req.toByteArray());
            byte[] reply = readFully(in, 10);
            if (reply[1] != 0) throw new IllegalStateException("SOCKS5 CONNECT refused: " + reply[1]);
            out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
            return body.toString("UTF-8");
        }
    }

    private static byte[] readFully(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IllegalStateException("closed after " + off + " of " + n + " bytes");
            off += r;
        }
        return b;
    }
}
