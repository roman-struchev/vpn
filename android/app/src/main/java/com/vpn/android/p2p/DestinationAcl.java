package com.vpn.android.p2p;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Mandatory destination-ACL for P2P relay nodes (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.8, added by the repo owner personally — not
 * optional). This device relays raw bytes from a connecting client (who we
 * cannot fully trust — see the doc's threat model) to whatever host:port that
 * client's signal names. Without this check, a malicious/compromised client
 * could direct us to open a TCP connection to our own home/office LAN
 * (192.168.x.x, a router's admin panel, an internal NAS, etc.) and use this
 * device as an SSRF pivot — exactly the same class of rule
 * agent/src/xray/config-builder.ts already enforces server-side for regular
 * nodes via `{ ip: ['geoip:private'], outboundTag: 'block' }`, just
 * re-implemented here since a P2P relay agent has no Xray-core routing
 * engine of its own (see P2pRelayAgent's class doc for why).
 */
public final class DestinationAcl {

    private DestinationAcl() {
    }

    private static final class Range {
        final int addressByteLength; // 4 (IPv4) or 16 (IPv6) — explicit, never derived from BigInteger bit-length (unreliable near zero, e.g. 0.0.0.0/8).
        final BigInteger start;
        final BigInteger end;

        Range(String cidr) {
            String[] parts = cidr.split("/");
            try {
                InetAddress base = InetAddress.getByName(parts[0]);
                int prefixLen = Integer.parseInt(parts[1]);
                this.addressByteLength = base.getAddress().length;
                BigInteger baseInt = new BigInteger(1, base.getAddress());
                int totalBits = addressByteLength * 8;
                BigInteger mask = BigInteger.ONE.shiftLeft(totalBits - prefixLen).subtract(BigInteger.ONE);
                this.start = baseInt.andNot(mask);
                this.end = start.or(mask);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("bad CIDR literal: " + cidr, e);
            }
        }

        boolean contains(InetAddress addr) {
            // A v4 range must never match a v6 address or vice versa, even if
            // their numeric values happen to overlap (e.g. 10.0.0.1 vs
            // ::a00:1) — guard on the actual address-family byte length.
            if (addr.getAddress().length != addressByteLength) {
                return false;
            }
            BigInteger value = new BigInteger(1, addr.getAddress());
            return value.compareTo(start) >= 0 && value.compareTo(end) <= 0;
        }
    }

    // RFC1918 + loopback + link-local + "this network" + IPv6 loopback/
    // link-local/unique-local — the exact set the doc's §8.8 asks us to
    // mirror from config-builder.ts's geoip:private rule.
    private static final List<String> BLOCKED_CIDRS = List.of(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "0.0.0.0/8",
            "::1/128",
            "fe80::/10",
            "fc00::/7"
    );

    private static final List<Range> RANGES = BLOCKED_CIDRS.stream().map(Range::new).toList();

    /**
     * Resolves {@code host} and, only if EVERY resolved address is safe,
     * returns the one address a caller should actually connect to. Returns
     * null if disallowed or unresolvable (fail closed).
     *
     * Callers that go on to open a real connection MUST connect to this
     * exact returned {@link InetAddress}, never re-resolve the hostname a
     * second time for the actual connect — re-resolving is what would let a
     * DNS-rebinding attacker pass this check against a public IP and then
     * have the real connection land on a private one moments later, once its
     * DNS record flips. This is why the method hands back an address object
     * rather than a plain boolean.
     */
    public static InetAddress resolveAllowedAddress(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return null;
            }
            for (InetAddress addr : addresses) {
                if (isPrivateOrReserved(addr)) {
                    return null;
                }
            }
            return addresses[0];
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Convenience boolean form of {@link #resolveAllowedAddress} for callers that only need a yes/no answer (e.g. tests) — never use this for a caller that will go on to actually connect, see that method's doc. */
    public static boolean isDestinationAllowed(String host, int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        return resolveAllowedAddress(host) != null;
    }

    private static boolean isPrivateOrReserved(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        for (Range range : RANGES) {
            if (range.contains(addr)) {
                return true;
            }
        }
        return false;
    }
}
