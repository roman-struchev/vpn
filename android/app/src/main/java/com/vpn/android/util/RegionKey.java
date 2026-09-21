package com.vpn.android.util;

/**
 * How a row in the region list is identified.
 *
 * A country can offer two different things to connect to at once: our own
 * servers there, and other members' devices there (a P2P exit — the traffic
 * leaves for the internet from that person's connection, under their IP).
 * Both rows carry the same label, so the label cannot be the identity: the
 * key is. Mirrors SubscriptionExportService's keyFor/isP2pKey/regionFromKey
 * server-side, which produces these, and desktop's shared/regionKey.ts.
 */
public final class RegionKey {

    private RegionKey() {
    }

    private static final String P2P_PREFIX = "p2p:";

    public static String forRegion(String region, boolean p2p) {
        return p2p ? P2P_PREFIX + region : region;
    }

    public static boolean isP2p(String key) {
        return key != null && key.startsWith(P2P_PREFIX);
    }

    /** The bare region inside a key, P2P-prefixed or not. */
    public static String regionOf(String key) {
        return isP2p(key) ? key.substring(P2P_PREFIX.length()) : key;
    }
}
