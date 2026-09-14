package com.vpn.android.api.model;

/**
 * One entry of GET /api/v1/user/regions — mirrors server
 * SubscriptionExportService.RegionSummary. loadLevel is one of
 * "LOW"/"MEDIUM"/"HIGH", a rough glance-able congestion indicator (not a
 * precise capacity model — see the server-side javadoc).
 */
public class RegionInfo {
    public String region;
    public int nodeCount;
    /** Null when no node in this region currently reports CPU via heartbeat. */
    public Double avgCpuPercent;
    public long avgActiveConnections;
    public String loadLevel;
    /**
     * Whether the caller's own subscription can actually connect through this
     * region right now (server compares the region's node pool against the
     * caller's effective tariff's server pool). false does NOT mean hidden —
     * paid regions are still listed to a trial user so they can see what a
     * higher plan unlocks; the client greys these out / blocks picking them
     * instead of silently reassigning elsewhere with a vague message.
     */
    public boolean accessible;
}
