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

    // ---- Exits: the same peers, asked about as something the user picked ----

    @Test
    void testListsExitsForAPaidPlan() {
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE"))
                .thenReturn(List.of(relay(1L, 99L, "ALWAYS", true, true)));

        List<Map<String, Object>> exits = directory.availableExitsFor(CALLER_ID, null);

        assertEquals(1, exits.size());
        assertEquals(1L, exits.get(0).get("nodeId"));
        assertEquals("Montenegro, Podgorica", exits.get(0).get("region"));
        assertFalse(exits.get(0).containsKey("hostname"), "a peer's hostname is never handed to another user");
        assertFalse(exits.get(0).containsKey("publicIp"));
    }

    @Test
    void testATrialPlanGetsNoExits() {
        // The paid/trial split is the whole gate here — deliberately not the
        // node's own pool flags, which are set both ways on this peer.
        givenTariffPool("trial");
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE"))
                .thenReturn(List.of(relay(1L, 99L, "ALWAYS", true, true)));

        assertTrue(directory.availableExitsFor(CALLER_ID, null).isEmpty(),
                "carrying a whole session on a volunteer's uplink is a paid-plan feature");
    }

    @Test
    void testExitsCanBeNarrowedToOneRegion() {
        Node here = relay(1L, 99L, "ALWAYS", true, true);
        Node elsewhere = relay(2L, 99L, "ALWAYS", true, true);
        elsewhere.setRegion("Germany, Berlin");
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE")).thenReturn(List.of(here, elsewhere));

        List<Map<String, Object>> exits = directory.availableExitsFor(CALLER_ID, "germany, berlin");

        assertEquals(1, exits.size(), "matched case-insensitively, like the VPS region scoping");
        assertEquals(2L, exits.get(0).get("nodeId"));
    }

    @Test
    void testExitsExcludeTheCallersOwnDeviceAndLapsedWindows() {
        Node mine = relay(1L, CALLER_ID, "ALWAYS", true, true);
        Node lapsed = relay(2L, 99L, "TIMED", true, true);
        lapsed.setRelayExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        Node usable = relay(3L, 99L, "ALWAYS", true, true);
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE")).thenReturn(List.of(mine, lapsed, usable));

        List<Map<String, Object>> exits = directory.availableExitsFor(CALLER_ID, null);

        assertEquals(1, exits.size());
        assertEquals(3L, exits.get(0).get("nodeId"));
    }

    // ---- What the signaling endpoint enforces (the lists above only inform) ----

    @Test
    void testAPaidPlanMayConnectThroughAnyEligiblePeer() {
        Node peer = relay(1L, 99L, "ALWAYS", false, false);
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(peer));

        assertTrue(directory.mayConnectThrough(CALLER_ID, 1L),
                "an exit is not limited by the node's pool flags — the caller's tariff is the rule");
    }

    @Test
    void testATrialPlanMayOnlyConnectThroughPeersOfferedToTrial() {
        givenTariffPool("trial");
        Node paidOnly = relay(1L, 99L, "ALWAYS", true, false);
        Node offeredToTrial = relay(2L, 99L, "ALWAYS", true, true);
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(paidOnly));
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(offeredToTrial));

        assertFalse(directory.mayConnectThrough(CALLER_ID, 1L));
        assertTrue(directory.mayConnectThrough(CALLER_ID, 2L));
    }

    @Test
    void testMayNotConnectThroughOwnDeviceALapsedWindowOrAnUnknownNode() {
        Node mine = relay(1L, CALLER_ID, "ALWAYS", true, true);
        Node lapsed = relay(2L, 99L, "TIMED", true, true);
        lapsed.setRelayExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        Node offline = relay(3L, 99L, "ALWAYS", true, true);
        offline.setStatus("OFFLINE");
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(mine));
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(lapsed));
        when(nodeRepository.findById(3L)).thenReturn(Optional.of(offline));
        when(nodeRepository.findById(4L)).thenReturn(Optional.empty());

        assertFalse(directory.mayConnectThrough(CALLER_ID, 1L));
        assertFalse(directory.mayConnectThrough(CALLER_ID, 2L));
        assertFalse(directory.mayConnectThrough(CALLER_ID, 3L));
        assertFalse(directory.mayConnectThrough(CALLER_ID, 4L));
        assertFalse(directory.mayConnectThrough(CALLER_ID, null));
    }

    @Test
    void testExitsComeFreshestFirst() {
        Node stale = relay(1L, 99L, "ALWAYS", true, true);
        stale.setLastHeartbeatAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        Node fresh = relay(2L, 99L, "ALWAYS", true, true);
        fresh.setLastHeartbeatAt(Instant.now());
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE")).thenReturn(List.of(stale, fresh));

        List<Map<String, Object>> exits = directory.availableExitsFor(CALLER_ID, null);

        assertEquals(List.of(2L, 1L), exits.stream().map(e -> e.get("nodeId")).toList(),
                "the client works down this list, so the peer most likely to still be there goes first");
    }

    @Test
    void aPeerIsNotOfferedWhileItsReachabilityIsCheckedNorAfterItFails() {
        givenTariffPool("paid");
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE")).thenReturn(List.of(
                relay(1L, 99L, "ALWAYS", true, true),
                relay(2L, 98L, "ALWAYS", true, true),
                relay(3L, 97L, "ALWAYS", true, true)));
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(relay(2L, 98L, "ALWAYS", true, true)));
        com.vpn.server.service.P2pReachabilityService reachability = org.mockito.Mockito.mock(com.vpn.server.service.P2pReachabilityService.class);
        when(reachability.isOffered(1L)).thenReturn(true);   // reachable
        when(reachability.isOffered(2L)).thenReturn(false);  // still being checked, or unreachable
        when(reachability.isOffered(3L)).thenReturn(true);   // checked: could not tell — offered as before
        directory.setReachability(reachability);

        assertEquals(java.util.Set.of(1L, 3L), directory.availableExitsFor(CALLER_ID, null).stream().map(e -> e.get("nodeId")).collect(java.util.stream.Collectors.toSet()));
        assertFalse(directory.mayConnectThrough(CALLER_ID, 2L), "and nobody can signal to it either");
    }

    @Test
    void aPeerBehindTheClientsCarrierNatUnderAnotherAddressIsNotOffered() {
        givenTariffPool("paid");
        when(nodeRepository.findByTypeAndStatus("p2p", "ONLINE")).thenReturn(List.of(
                relay(1L, 99L, "ALWAYS", true, true),
                relay(2L, 98L, "ALWAYS", true, true)));
        com.vpn.server.service.P2pReachabilityService reachability = org.mockito.Mockito.mock(com.vpn.server.service.P2pReachabilityService.class);
        when(reachability.isOffered(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(reachability.sameCarrierNetwork(1L, "79.143.107.32")).thenReturn(true);
        directory.setReachability(reachability);

        assertEquals(List.of(2L), directory.availableExitsFor(CALLER_ID, null, "79.143.107.32").stream().map(e -> e.get("nodeId")).toList());
        assertEquals(List.of(2L), directory.availableRelaysFor(CALLER_ID, "79.143.107.32").stream().map(e -> e.get("nodeId")).toList());
        assertEquals(2, directory.availableExitsFor(CALLER_ID, null).size(), "no client address known: nothing is left out");
    }
}
