import { describe, expect, it } from 'vitest';
import { TransportFallbackPolicy } from '../src/shared/transportFallbackPolicy';

describe('TransportFallbackPolicy', () => {
  it('stays on XHTTP until every node has been tried', () => {
    const policy = new TransportFallbackPolicy(3, true);
    expect(policy.getCurrentTransport()).toBe('XHTTP');
    expect(policy.onNodeSwitch().transportChanged).toBe(false);
    expect(policy.onNodeSwitch().transportChanged).toBe(false);
    expect(policy.getCurrentTransport()).toBe('XHTTP');
  });

  it('switches to gRPC after exhausting all nodes on XHTTP', () => {
    const policy = new TransportFallbackPolicy(2, true);
    policy.onNodeSwitch();
    const outcome = policy.onNodeSwitch();
    expect(outcome.transportChanged).toBe(true);
    expect(outcome.allTransportsExhausted).toBe(false);
    expect(policy.getCurrentTransport()).toBe('GRPC');
  });

  it('reports exhaustion after gRPC also fails on every node', () => {
    const policy = new TransportFallbackPolicy(1, true);
    expect(policy.onNodeSwitch().transportChanged).toBe(true);
    const exhausted = policy.onNodeSwitch();
    expect(exhausted.transportChanged).toBe(false);
    expect(exhausted.allTransportsExhausted).toBe(true);
  });

  it('skips gRPC entirely when not advertised by the server', () => {
    const policy = new TransportFallbackPolicy(1, false);
    const outcome = policy.onNodeSwitch();
    expect(outcome.transportChanged).toBe(false);
    expect(outcome.allTransportsExhausted).toBe(true);
    expect(policy.getCurrentTransport()).toBe('XHTTP');
  });

  it('clamps node count to at least one', () => {
    const policy = new TransportFallbackPolicy(0, true);
    expect(policy.onNodeSwitch().transportChanged).toBe(true);
  });
});
