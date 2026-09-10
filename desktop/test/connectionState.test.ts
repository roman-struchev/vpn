import { describe, expect, it } from 'vitest';
import { ConnectionStateMachine, InvalidTransitionError } from '../src/shared/connectionState';

describe('ConnectionStateMachine', () => {
  it('starts disconnected', () => {
    expect(new ConnectionStateMachine().getState()).toBe('DISCONNECTED');
  });

  it('happy path: connect then disconnect', () => {
    const m = new ConnectionStateMachine();
    m.dispatch('CONNECT_REQUESTED');
    expect(m.getState()).toBe('CONNECTING');
    m.dispatch('TUNNEL_UP');
    expect(m.getState()).toBe('CONNECTED');
    m.dispatch('DISCONNECT_REQUESTED');
    expect(m.getState()).toBe('DISCONNECTED');
  });

  it('a drop while connected goes to reconnecting, not error', () => {
    const m = new ConnectionStateMachine();
    m.dispatch('CONNECT_REQUESTED');
    m.dispatch('TUNNEL_UP');
    m.dispatch('TUNNEL_DOWN');
    expect(m.getState()).toBe('RECONNECTING');
  });

  it('reconnecting can recover to connected', () => {
    const m = new ConnectionStateMachine();
    m.dispatch('CONNECT_REQUESTED');
    m.dispatch('TUNNEL_UP');
    m.dispatch('TUNNEL_DOWN');
    m.dispatch('TUNNEL_UP');
    expect(m.getState()).toBe('CONNECTED');
  });

  it('an operator block during reconnect shows the honest screen, not a generic error', () => {
    const m = new ConnectionStateMachine();
    m.dispatch('CONNECT_REQUESTED');
    m.dispatch('TUNNEL_DOWN');
    m.dispatch('OPERATOR_BLOCK_DETECTED');
    expect(m.getState()).toBe('OPERATOR_BLOCKED');
  });

  it('can retry from error and from operator-blocked', () => {
    const m = new ConnectionStateMachine();
    m.dispatch('CONNECT_REQUESTED');
    m.dispatch('FATAL_ERROR');
    expect(m.getState()).toBe('ERROR');
    m.dispatch('CONNECT_REQUESTED');
    expect(m.getState()).toBe('CONNECTING');
  });

  it('rejects tunnel-up without having requested a connection', () => {
    const m = new ConnectionStateMachine();
    expect(() => m.dispatch('TUNNEL_UP')).toThrow(InvalidTransitionError);
  });

  it('rejects a second disconnect once already disconnected', () => {
    const m = new ConnectionStateMachine();
    m.dispatch('CONNECT_REQUESTED');
    m.dispatch('DISCONNECT_REQUESTED');
    expect(() => m.dispatch('DISCONNECT_REQUESTED')).toThrow(InvalidTransitionError);
  });
});
