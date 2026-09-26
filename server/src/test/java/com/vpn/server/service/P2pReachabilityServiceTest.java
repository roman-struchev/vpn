package com.vpn.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2pReachabilityServiceTest {

    private final P2pReachabilityService service = new P2pReachabilityService(null, null);

    @Test
    void aClientOnTheSameCarrierPoolUnderAnotherAddressCannotReachThePeer() {
        // Seen live: a phone relaying on One Crna Gora's LTE, a laptop on an LTE router of the same carrier.
        service.setStateForTests(13L, P2pReachabilityService.State.REACHABLE, "79.143.107.94");
        assertTrue(service.sameCarrierNetwork(13L, "79.143.107.32"));
    }

    @Test
    void theSameAddressAnotherNetworkOrNoAddressAreNotExcluded() {
        service.setStateForTests(13L, P2pReachabilityService.State.REACHABLE, "79.143.107.94");
        assertFalse(service.sameCarrierNetwork(13L, "79.143.107.94"), "usually one home network: they meet over local addresses");
        assertFalse(service.sameCarrierNetwork(13L, "37.27.250.158"));
        assertFalse(service.sameCarrierNetwork(13L, "2a01:4f9:c014:7644::1"));
        assertFalse(service.sameCarrierNetwork(13L, null));
        assertFalse(service.sameCarrierNetwork(14L, "79.143.107.32"), "never checked");
        service.setStateForTests(15L, P2pReachabilityService.State.INCONCLUSIVE);
        assertFalse(service.sameCarrierNetwork(15L, "79.143.107.32"), "no address learned");
    }
}
