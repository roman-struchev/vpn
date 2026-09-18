package com.vpn.android.api.model;

/**
 * One relay peer from GET /api/v1/user/p2p/relays — a device this account may
 * route a connection *through*. Deliberately carries no address: a relay is
 * somebody's personal phone or laptop, reached over WebRTC by id, never dialed.
 */
public class RelayInfo {
    public long nodeId;
    public String region;
    public int activeConnections;
    public String lastSeenAt;
}
