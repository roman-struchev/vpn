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
}
