package com.vpn.server.p2p;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReachabilityProbe against a fake relaying device on loopback that speaks
 * just enough ICE: it answers the offer with its credentials and a candidate,
 * and answers connectivity checks signed with its password.
 */
class ReachabilityProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private DatagramSocket device;
    private DatagramSocket server;

    @AfterEach
    void close() {
        if (device != null) device.close();
        if (server != null) server.close();
    }

    /** A device that answers checks on its socket, advertising the given port (its own, or one nothing listens on). */
    private ReachabilityProbe.Signaling fakeDevice(boolean answersOffer, Integer advertisedPortOverride) throws Exception {
        device = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        String ufrag = "dev1";
        String password = "devicepassword0123456789";
        Thread responder = new Thread(() -> {
            byte[] buf = new byte[1500];
            while (!device.isClosed()) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    device.receive(in);
                    if (IceStun.type(in.getData(), in.getLength()) != IceStun.BINDING_REQUEST) continue;
                    byte[] reply = IceStun.bindingSuccess(IceStun.transactionId(in.getData()),
                            (InetSocketAddress) in.getSocketAddress(), password);
                    device.send(new DatagramPacket(reply, reply.length, in.getSocketAddress()));
                } catch (Exception e) {
                    return;
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        int advertised = advertisedPortOverride != null ? advertisedPortOverride : device.getLocalPort();
        BlockingQueue<byte[]> toServer = new LinkedBlockingQueue<>();
        return new ReachabilityProbe.Signaling() {
            @Override
            public void send(byte[] envelope) throws Exception {
                JsonNode offer = JSON.readTree(envelope);
                assertEquals("offer", offer.path("kind").asText());
                assertTrue(offer.path("sdp").asText().contains("a=ice-ufrag:"), "a real offer");
                if (!answersOffer) return;
                String answer = "v=0\r\na=ice-ufrag:" + ufrag + "\r\na=ice-pwd:" + password + "\r\n";
                toServer.add(JSON.writeValueAsBytes(java.util.Map.of("kind", "answer", "sdp", answer)));
                toServer.add(JSON.writeValueAsBytes(java.util.Map.of("kind", "ice", "sdpMid", "0",
                        "candidate", "candidate:1 1 UDP 1686052607 127.0.0.1 " + advertised + " typ srflx raddr 0.0.0.0 rport 0")));
            }

            @Override
            public byte[] poll(long waitMs) throws Exception {
                return toServer.poll(waitMs, TimeUnit.MILLISECONDS);
            }
        };
    }

    private ReachabilityProbe.Result probe(ReachabilityProbe.Signaling signaling, long timeoutMs) throws Exception {
        server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        return new ReachabilityProbe(null, "203.0.113.1", 8080, timeoutMs).allowPrivateCandidatesForTests()
                .run(signaling, server, new InetSocketAddress("127.0.0.1", server.getLocalPort()));
    }

    @Test
    void aDeviceThatAnswersOnItsAdvertisedAddressIsReachable() throws Exception {
        ReachabilityProbe.Result r = probe(fakeDevice(true, null), 3000);
        assertEquals(ReachabilityProbe.Verdict.REACHABLE, r.verdict(), r.detail());
    }

    @Test
    void aDeviceWhoseAdvertisedAddressLeadsNowhereIsUnreachable() throws Exception {
        // What a symmetric NAT looks like from outside: the address the device
        // advertised is not where its packets actually come from.
        int nothingListens;
        try (DatagramSocket spare = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            nothingListens = spare.getLocalPort();
        }
        ReachabilityProbe.Result r = probe(fakeDevice(true, nothingListens), 1500);
        assertEquals(ReachabilityProbe.Verdict.UNREACHABLE, r.verdict(), r.detail());
    }

    @Test
    void aDeviceThatNeverAnswersTheOfferGivesNoVerdict() throws Exception {
        ReachabilityProbe.Result r = probe(fakeDevice(false, null), 1000);
        assertEquals(ReachabilityProbe.Verdict.INCONCLUSIVE, r.verdict(), r.detail());
    }

    @Test
    void aResponseSignedWithTheWrongPasswordDoesNotCount() {
        byte[] tx = IceStun.newTransactionId();
        byte[] msg = IceStun.bindingSuccess(tx, new InetSocketAddress("198.51.100.1", 1234), "right-password");
        assertTrue(IceStun.hasValidIntegrity(msg, msg.length, "right-password"));
        assertEquals(false, IceStun.hasValidIntegrity(msg, msg.length, "wrong-password"));
    }

    @Test
    void privateAndCarrierGradeAddressesAreNotPublic() throws Exception {
        for (String ip : new String[]{"10.166.52.243", "192.168.8.145", "100.64.1.2", "127.0.0.1"}) {
            assertEquals(false, ReachabilityProbe.isPublic(InetAddress.getByName(ip)), ip);
        }
        assertTrue(ReachabilityProbe.isPublic(InetAddress.getByName("79.143.107.94")));
    }
}
