package com.vpn.android.p2p;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake STUN servers on loopback, each reporting the public port it is told
 * to: the same port from both is a NAT relaying can work behind, different
 * ports is a symmetric one (a mobile carrier), silence is blocked UDP.
 */
public class NatCheckTest {

    private final List<DatagramSocket> servers = new ArrayList<>();

    @After
    public void closeServers() {
        for (DatagramSocket s : servers) s.close();
    }

    private InetSocketAddress fakeStun(Integer reportPort) throws Exception {
        DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        servers.add(s);
        Thread t = new Thread(() -> {
            byte[] buf = new byte[512];
            while (!s.isClosed()) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    s.receive(in);
                    if (reportPort == null) continue;
                    byte[] res = xorMapped(new byte[]{(byte) 203, 0, 113, 7}, reportPort, in.getData());
                    s.send(new DatagramPacket(res, res.length, in.getSocketAddress()));
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return InetSocketAddress.createUnresolved("127.0.0.1", s.getLocalPort());
    }

    static byte[] xorMapped(byte[] ip, int port, byte[] request) {
        byte[] res = new byte[32];
        res[0] = 0x01;
        res[1] = 0x01;
        res[3] = 12;
        System.arraycopy(request, 4, res, 4, 16);
        res[20] = 0x00;
        res[21] = 0x20;
        res[23] = 8;
        res[25] = 0x01;
        int xport = port ^ 0x2112;
        res[26] = (byte) (xport >>> 8);
        res[27] = (byte) xport;
        byte[] cookie = {0x21, 0x12, (byte) 0xa4, 0x42};
        for (int i = 0; i < 4; i++) res[28 + i] = (byte) (ip[i] ^ cookie[i]);
        return res;
    }

    @Test
    public void onePublicPortForBothServersIsFine() throws Exception {
        assertEquals(NatCheck.Verdict.OK, NatCheck.check(List.of(fakeStun(40000), fakeStun(40000)), 500));
    }

    @Test
    public void aNewPublicPortPerServerIsASymmetricNat() throws Exception {
        assertEquals(NatCheck.Verdict.SYMMETRIC, NatCheck.check(List.of(fakeStun(40000), fakeStun(40001)), 500));
    }

    @Test
    public void noAnswerAtAllIsBlockedUdp() throws Exception {
        assertEquals(NatCheck.Verdict.NO_UDP, NatCheck.check(List.of(fakeStun(null), fakeStun(null)), 300));
    }

    @Test
    public void oneAnswerCannotTellAndIsNotRefused() throws Exception {
        NatCheck.Verdict v = NatCheck.check(List.of(fakeStun(40000), fakeStun(null)), 300);
        assertEquals(NatCheck.Verdict.UNKNOWN, v);
        assertEquals(false, NatCheck.refuses(v));
    }

    @Test
    public void readsTheXorMappedAddressARealServerSends() {
        byte[] req = NatCheck.bindingRequest(new byte[12]);
        assertEquals("79.143.107.32:55021",
                NatCheck.parseMappedAddress(xorMapped(new byte[]{79, (byte) 143, 107, 32}, 55021, req)));
    }
}
