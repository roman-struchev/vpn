package com.vpn.android.vpn.state;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ConnectionStateMachineTest {

    private ConnectionStateMachine machine;

    @Before
    public void setUp() {
        machine = new ConnectionStateMachine();
    }

    @Test
    public void startsDisconnected() {
        assertEquals(ConnectionState.DISCONNECTED, machine.getState());
    }

    @Test
    public void happyPathConnectAndDisconnect() {
        machine.dispatch(ConnectionEvent.CONNECT_REQUESTED);
        assertEquals(ConnectionState.CONNECTING, machine.getState());

        machine.dispatch(ConnectionEvent.TUNNEL_UP);
        assertEquals(ConnectionState.CONNECTED, machine.getState());

        machine.dispatch(ConnectionEvent.DISCONNECT_REQUESTED);
        assertEquals(ConnectionState.DISCONNECTED, machine.getState());
    }

    @Test
    public void dropWhileConnectedGoesToReconnectingNotError() {
        machine.dispatch(ConnectionEvent.CONNECT_REQUESTED);
        machine.dispatch(ConnectionEvent.TUNNEL_UP);
        machine.dispatch(ConnectionEvent.TUNNEL_DOWN);
        assertEquals(ConnectionState.RECONNECTING, machine.getState());
    }

    @Test
    public void reconnectingCanRecoverToConnected() {
        machine.dispatch(ConnectionEvent.CONNECT_REQUESTED);
        machine.dispatch(ConnectionEvent.TUNNEL_UP);
        machine.dispatch(ConnectionEvent.TUNNEL_DOWN);
        machine.dispatch(ConnectionEvent.TUNNEL_UP);
        assertEquals(ConnectionState.CONNECTED, machine.getState());
    }

    @Test
    public void operatorBlockDuringReconnectShowsHonestScreenInsteadOfGenericError() {
        machine.dispatch(ConnectionEvent.CONNECT_REQUESTED);
        machine.dispatch(ConnectionEvent.TUNNEL_DOWN);
        machine.dispatch(ConnectionEvent.OPERATOR_BLOCK_DETECTED);
        assertEquals(ConnectionState.OPERATOR_BLOCKED, machine.getState());
    }

    @Test
    public void canRetryFromErrorAndFromOperatorBlocked() {
        machine.dispatch(ConnectionEvent.CONNECT_REQUESTED);
        machine.dispatch(ConnectionEvent.FATAL_ERROR);
        assertEquals(ConnectionState.ERROR, machine.getState());
        machine.dispatch(ConnectionEvent.CONNECT_REQUESTED);
        assertEquals(ConnectionState.CONNECTING, machine.getState());
    }

    @Test
    public void disconnectRequestedIsAlwaysHonoredExceptWhenAlreadyDisconnected() {
        machine.dispatch(ConnectionEvent.CONNECT_REQUESTED);
        machine.dispatch(ConnectionEvent.DISCONNECT_REQUESTED);
        assertEquals(ConnectionState.DISCONNECTED, machine.getState());

        assertThrows(IllegalStateException.class, () -> machine.dispatch(ConnectionEvent.DISCONNECT_REQUESTED));
    }

    @Test
    public void rejectsTunnelUpWithoutHavingRequestedAConnection() {
        assertThrows(IllegalStateException.class, () -> machine.dispatch(ConnectionEvent.TUNNEL_UP));
    }
}
