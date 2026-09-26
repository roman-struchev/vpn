package com.vpn.android.p2p;

import static org.junit.Assert.assertEquals;

import com.vpn.android.R;

import org.junit.Test;

/** Which explanation a saved reason shows: the device's own STUN check, or the server's reachability check. */
public class UnsupportedNetworkTextTest {

    @Test
    public void eachReasonHasItsExplanation() {
        assertEquals(R.string.p2p_relay_unsupported_unreachable, P2pRelayService.unsupportedNetworkText("UNREACHABLE"));
        assertEquals(R.string.p2p_relay_unsupported_symmetric, P2pRelayService.unsupportedNetworkText("SYMMETRIC"));
        assertEquals(R.string.p2p_relay_unsupported_no_udp, P2pRelayService.unsupportedNetworkText("NO_UDP"));
        assertEquals(R.string.p2p_relay_unsupported_symmetric, P2pRelayService.unsupportedNetworkText("SOMETHING_NEWER"));
    }
}
