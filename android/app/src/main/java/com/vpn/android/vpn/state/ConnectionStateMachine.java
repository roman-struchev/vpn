package com.vpn.android.vpn.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure, Android-free state machine for the VPN connection lifecycle. Kept
 * framework-independent so it is exercised by plain JUnit tests, per
 * PLAN.md §5's "общий набор сценариев-тестов" idea: every native client is
 * expected to honor the same reconnect/backoff contract even without a
 * shared codebase.
 *
 * Not thread-safe by design: callers (XrayVpnService) own a single instance
 * per VPN session and must dispatch events from one thread/looper.
 */
public class ConnectionStateMachine {

    private static final Map<ConnectionState, Set<ConnectionEvent>> ALLOWED = new EnumMap<>(ConnectionState.class);

    static {
        ALLOWED.put(ConnectionState.DISCONNECTED, EnumSet.of(ConnectionEvent.CONNECT_REQUESTED));
        ALLOWED.put(ConnectionState.CONNECTING, EnumSet.of(
                ConnectionEvent.TUNNEL_UP,
                ConnectionEvent.TUNNEL_DOWN,
                ConnectionEvent.OPERATOR_BLOCK_DETECTED,
                ConnectionEvent.FATAL_ERROR,
                ConnectionEvent.DISCONNECT_REQUESTED));
        ALLOWED.put(ConnectionState.CONNECTED, EnumSet.of(
                ConnectionEvent.TUNNEL_DOWN,
                ConnectionEvent.DISCONNECT_REQUESTED));
        ALLOWED.put(ConnectionState.RECONNECTING, EnumSet.of(
                ConnectionEvent.TUNNEL_UP,
                ConnectionEvent.TUNNEL_DOWN,
                ConnectionEvent.OPERATOR_BLOCK_DETECTED,
                ConnectionEvent.FATAL_ERROR,
                ConnectionEvent.DISCONNECT_REQUESTED));
        ALLOWED.put(ConnectionState.OPERATOR_BLOCKED, EnumSet.of(
                ConnectionEvent.CONNECT_REQUESTED,
                ConnectionEvent.DISCONNECT_REQUESTED));
        ALLOWED.put(ConnectionState.ERROR, EnumSet.of(
                ConnectionEvent.CONNECT_REQUESTED,
                ConnectionEvent.DISCONNECT_REQUESTED));
    }

    private ConnectionState state = ConnectionState.DISCONNECTED;

    public ConnectionState getState() {
        return state;
    }

    /**
     * @throws IllegalStateException if the event is not valid from the current state.
     */
    public ConnectionState dispatch(ConnectionEvent event) {
        Set<ConnectionEvent> allowedEvents = ALLOWED.get(state);
        if (allowedEvents == null || !allowedEvents.contains(event)) {
            throw new IllegalStateException("Event " + event + " is not valid from state " + state);
        }
        state = nextState(state, event);
        return state;
    }

    private static ConnectionState nextState(ConnectionState from, ConnectionEvent event) {
        switch (event) {
            case CONNECT_REQUESTED:
                return ConnectionState.CONNECTING;
            case TUNNEL_UP:
                return ConnectionState.CONNECTED;
            case TUNNEL_DOWN:
                // A drop from CONNECTING or CONNECTED always lands in RECONNECTING first;
                // ERROR is reserved for FATAL_ERROR (config/auth problems Smart Reconnect
                // Backoff cannot fix by retrying).
                return ConnectionState.RECONNECTING;
            case OPERATOR_BLOCK_DETECTED:
                return ConnectionState.OPERATOR_BLOCKED;
            case FATAL_ERROR:
                return ConnectionState.ERROR;
            case DISCONNECT_REQUESTED:
                return ConnectionState.DISCONNECTED;
            default:
                throw new IllegalArgumentException("Unhandled event " + event);
        }
    }
}
