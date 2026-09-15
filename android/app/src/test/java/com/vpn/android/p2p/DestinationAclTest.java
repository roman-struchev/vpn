package com.vpn.android.p2p;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustive coverage of the mandatory destination-ACL (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.8) — this is the ONLY thing standing between a
 * malicious connecting client and using this device as an SSRF pivot into
 * its owner's home/office LAN, so every blocked range gets its own case
 * rather than a single parameterized loop that could hide a family-specific
 * (v4 vs v6) miss.
 */
public class DestinationAclTest {

    @Test
    public void allowsOrdinaryPublicIpv4() {
        assertTrue(DestinationAcl.isDestinationAllowed("8.8.8.8", 443));
    }

    @Test
    public void allowsOrdinaryPublicIpv6() {
        assertTrue(DestinationAcl.isDestinationAllowed("2001:4860:4860::8888", 443));
    }

    @Test
    public void blocksRfc1918_10Slash8() {
        assertFalse(DestinationAcl.isDestinationAllowed("10.0.0.1", 443));
        assertFalse(DestinationAcl.isDestinationAllowed("10.255.255.254", 443));
    }

    @Test
    public void blocksRfc1918_172_16Slash12() {
        assertFalse(DestinationAcl.isDestinationAllowed("172.16.0.1", 443));
        assertFalse(DestinationAcl.isDestinationAllowed("172.31.255.254", 443));
        // Just outside the /12 range — must NOT be blocked by an off-by-one.
        assertTrue(DestinationAcl.isDestinationAllowed("172.32.0.1", 443));
    }

    @Test
    public void blocksRfc1918_192_168Slash16() {
        assertFalse(DestinationAcl.isDestinationAllowed("192.168.1.1", 80));
    }

    @Test
    public void blocksLoopback() {
        assertFalse(DestinationAcl.isDestinationAllowed("127.0.0.1", 22));
        assertFalse(DestinationAcl.isDestinationAllowed("127.255.255.255", 22));
        assertFalse(DestinationAcl.isDestinationAllowed("localhost", 22));
    }

    @Test
    public void blocksLinkLocal() {
        assertFalse(DestinationAcl.isDestinationAllowed("169.254.1.1", 443));
    }

    @Test
    public void blocksThisNetwork() {
        assertFalse(DestinationAcl.isDestinationAllowed("0.0.0.0", 443));
    }

    @Test
    public void blocksIpv6Loopback() {
        assertFalse(DestinationAcl.isDestinationAllowed("::1", 443));
    }

    @Test
    public void blocksIpv6LinkLocal() {
        assertFalse(DestinationAcl.isDestinationAllowed("fe80::1", 443));
    }

    @Test
    public void blocksIpv6UniqueLocal() {
        assertFalse(DestinationAcl.isDestinationAllowed("fd12:3456:789a::1", 443));
    }

    @Test
    public void blocksUnresolvableHost_failsClosed() {
        assertFalse(DestinationAcl.isDestinationAllowed("this-host-does-not-exist.invalid", 443));
    }

    @Test
    public void blocksBlankOrInvalidInput() {
        assertFalse(DestinationAcl.isDestinationAllowed("", 443));
        assertFalse(DestinationAcl.isDestinationAllowed(null, 443));
        assertFalse(DestinationAcl.isDestinationAllowed("8.8.8.8", 0));
        assertFalse(DestinationAcl.isDestinationAllowed("8.8.8.8", 70000));
    }

    /**
     * P2pTcpBridge must connect using this exact returned address, never
     * re-resolve the hostname itself — otherwise a DNS-rebinding attacker
     * could pass this check against a public IP and have the real connect
     * land on a private one once the hostname's record changes moments
     * later. These cases guard the resolve-once contract directly, since
     * isDestinationAllowed's boolean form can't reveal a regression here.
     */
    @Test
    public void resolveAllowedAddress_returnsTheResolvedAddress_forAnAllowedHost() throws Exception {
        assertNotNull(DestinationAcl.resolveAllowedAddress("8.8.8.8"));
        assertEquals(InetAddress.getByName("8.8.8.8"), DestinationAcl.resolveAllowedAddress("8.8.8.8"));
    }

    @Test
    public void resolveAllowedAddress_returnsNull_forABlockedHost() {
        assertNull(DestinationAcl.resolveAllowedAddress("192.168.1.1"));
        assertNull(DestinationAcl.resolveAllowedAddress("127.0.0.1"));
    }

    @Test
    public void v4AndV6RangesNeverCrossMatch() {
        // 10.0.0.1 numerically embeds inside some IPv6 literal representations
        // (e.g. ::a00:1) — the address-family guard in DestinationAcl.Range
        // must keep these from being confused with each other.
        assertTrue(DestinationAcl.isDestinationAllowed("::a00:1", 443));
    }
}
