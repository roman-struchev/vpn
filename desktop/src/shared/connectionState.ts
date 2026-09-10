/**
 * Same state set as the Android client
 * (android/app/src/main/java/com/vpn/android/vpn/state/) and PLAN.md §9:
 * Disconnected / Connecting / Connected / Reconnecting / Error, plus a
 * distinct OperatorBlocked state for the honest blocking screen. No shared
 * code across platforms per PLAN.md §5 — only the same contract, each
 * platform re-implements and unit-tests it natively.
 */
export type ConnectionState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'RECONNECTING'
  | 'OPERATOR_BLOCKED'
  | 'ERROR';

export type ConnectionEvent =
  | 'CONNECT_REQUESTED'
  | 'TUNNEL_UP'
  | 'TUNNEL_DOWN'
  | 'OPERATOR_BLOCK_DETECTED'
  | 'FATAL_ERROR'
  | 'DISCONNECT_REQUESTED';

const ALLOWED: Record<ConnectionState, ConnectionEvent[]> = {
  DISCONNECTED: ['CONNECT_REQUESTED'],
  CONNECTING: ['TUNNEL_UP', 'TUNNEL_DOWN', 'OPERATOR_BLOCK_DETECTED', 'FATAL_ERROR', 'DISCONNECT_REQUESTED'],
  CONNECTED: ['TUNNEL_DOWN', 'DISCONNECT_REQUESTED'],
  RECONNECTING: ['TUNNEL_UP', 'TUNNEL_DOWN', 'OPERATOR_BLOCK_DETECTED', 'FATAL_ERROR', 'DISCONNECT_REQUESTED'],
  OPERATOR_BLOCKED: ['CONNECT_REQUESTED', 'DISCONNECT_REQUESTED'],
  ERROR: ['CONNECT_REQUESTED', 'DISCONNECT_REQUESTED'],
};

function nextState(event: ConnectionEvent): ConnectionState {
  switch (event) {
    case 'CONNECT_REQUESTED':
      return 'CONNECTING';
    case 'TUNNEL_UP':
      return 'CONNECTED';
    case 'TUNNEL_DOWN':
      // A drop always lands in RECONNECTING first; ERROR is reserved for
      // FATAL_ERROR (config/auth problems retrying can't fix).
      return 'RECONNECTING';
    case 'OPERATOR_BLOCK_DETECTED':
      return 'OPERATOR_BLOCKED';
    case 'FATAL_ERROR':
      return 'ERROR';
    case 'DISCONNECT_REQUESTED':
      return 'DISCONNECTED';
  }
}

export class InvalidTransitionError extends Error {
  constructor(event: ConnectionEvent, from: ConnectionState) {
    super(`Event ${event} is not valid from state ${from}`);
    this.name = 'InvalidTransitionError';
  }
}

export class ConnectionStateMachine {
  private state: ConnectionState = 'DISCONNECTED';

  getState(): ConnectionState {
    return this.state;
  }

  dispatch(event: ConnectionEvent): ConnectionState {
    if (!ALLOWED[this.state].includes(event)) {
      throw new InvalidTransitionError(event, this.state);
    }
    this.state = nextState(event);
    return this.state;
  }
}
