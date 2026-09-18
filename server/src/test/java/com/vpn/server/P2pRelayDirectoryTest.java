package com.vpn.server;

import com.vpn.server.entity.*;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.service.P2pRelayDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * What a client may be told about relay peers. A relay is somebody's personal
 * laptop or phone, so this list is as much about what it must NOT contain as
 * about what it does.
 */
class P2pRelayDirectoryTest {

    private NodeRepository nodeRepository;
    private SubscriptionRepository subscriptionRepository;
    private P2pRelayDirectory directory;

    private static final long CALLER_ID = 11L;

    @BeforeEach
    void setUp() {
        nodeRepository = mock(NodeRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        directory = new P2pRelayDirectory(nodeRepository, subscriptionRepository);
        givenTariffPool("paid");
    }

    private void givenTariffPool(String pool) {
        User user = new User();
        user.setId(CALLER_ID);
        Tariff tariff = new Tariff();
        tariff.setId(pool);
        tariff.setServerPool(pool);
        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(tariff);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(CALLER_ID, "ACTIVE"))
                .thenReturn(Optional.of(sub));
    }

    private static Node relay(long id, Long ownerId, String relayMode, boolean paid, boolean trial) {
        Node node = new Node();
        node.setId(id);
        node.setType("p2p");
        node.setStatus("ONLINE");
        node.setRegion("Montenegro, Podgorica");
        node.setHostname("somebodys-laptop");
        node.setPublicIp("203.0.113.5");
        node.setRelayMode(relayMode);
        node.setAvailableToPaid(paid);
        node.setAvailableToTrial(trial);
        node.setLastHeartbeatAt(Instant.now());
        if (ownerId != null) {
            User owner = new User();
            owner.setId(ownerId);
            node.setOwnerUser(owner);
        }
        return node;
    }

    @Test
    void testListsRelaysThisAccountMayConnectThrough() {
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE"))
                .thenReturn(List.of(relay(1L, 99L, "ALWAYS", true, true)));

        List<Map<String, Object>> relays = directory.availableRelaysFor(CALLER_ID);

        assertEquals(1, relays.size());
        assertEquals(1L, relays.get(0).get("nodeId"));
        assertEquals("Montenegro, Podgorica", relays.get(0).get("region"));
    }

    @Test
    void testNeverRevealsWhoseDeviceItIsOrWhereItLives() {
        // A relay is a personal device: the client needs an id to signal to and
        // a region to choose by, and reaches it over WebRTC — never by address.
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE"))
                .thenReturn(List.of(relay(1L, 99L, "ALWAYS", true, true)));

        Map<String, Object> entry = directory.availableRelaysFor(CALLER_ID).get(0);

        assertFalse(entry.containsKey("publicIp"), entry.toString());
        assertFalse(entry.containsKey("hostname"), entry.toString());
        assertFalse(entry.containsKey("ownerUserId"), entry.toString());
        assertFalse(entry.toString().contains("203.0.113.5"), entry.toString());
        assertFalse(entry.toString().contains("somebodys-laptop"), entry.toString());
    }

    @Test
    void testOmitsTheCallersOwnDevice() {
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE"))
                .thenReturn(List.of(relay(1L, CALLER_ID, "ALWAYS", true, true)));

        assertTrue(directory.availableRelaysFor(CALLER_ID).isEmpty(),
                "relaying through your own device circumvents nothing and would credit you for your own bytes");
    }

    @Test
    void testOmitsARelayWhoseWindowIsOver() {
        // A TIMED window can lapse between heartbeats, and handing out such a
        // node would only produce a session it refuses anyway.
        Node lapsed = relay(1L, 99L, "TIMED", true, true);
        lapsed.setRelayExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        Node stillOffering = relay(2L, 99L, "TIMED", true, true);
        stillOffering.setRelayExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE")).thenReturn(List.of(lapsed, stillOffering));

        List<Map<String, Object>> relays = directory.availableRelaysFor(CALLER_ID);

        assertEquals(1, relays.size());
        assertEquals(2L, relays.get(0).get("nodeId"));
    }

    @Test
    void testRespectsTheTariffPoolTheRelayWasOfferedTo() {
        givenTariffPool("trial");
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE"))
                .thenReturn(List.of(relay(1L, 99L, "ALWAYS", true, false), relay(2L, 99L, "ALWAYS", true, true)));

        List<Map<String, Object>> relays = directory.availableRelaysFor(CALLER_ID);

        assertEquals(1, relays.size());
        assertEquals(2L, relays.get(0).get("nodeId"), "a trial account only reaches relays offered to trial");
    }

    @Test
    void testNoRelaysOnlineIsAnOrdinaryEmptyAnswer() {
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE")).thenReturn(List.of());

        assertTrue(directory.availableRelaysFor(CALLER_ID).isEmpty());
    }
}
