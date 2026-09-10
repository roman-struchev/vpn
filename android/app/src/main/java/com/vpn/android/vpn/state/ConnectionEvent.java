package com.vpn.android.vpn.state;

public enum ConnectionEvent {
    CONNECT_REQUESTED,
    TUNNEL_UP,
    TUNNEL_DOWN,
    OPERATOR_BLOCK_DETECTED,
    FATAL_ERROR,
    DISCONNECT_REQUESTED
}
