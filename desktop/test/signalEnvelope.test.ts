import { describe, expect, it } from 'vitest';
import { decodeSignal, encodeSignal, type SignalEnvelope } from '../src/main/p2p/signalEnvelope';

// This exact wire format (docs/research/P2P_RELAY_FEASIBILITY.md §8.1) is
// shared verbatim across the Desktop/Android/Linux-agent relay
// implementations, built independently in parallel — a round-trip test here
// is the only thing standing between "interoperates" and "silently doesn't".
describe('signalEnvelope', () => {
  it.each<SignalEnvelope>([
    { kind: 'offer', sdp: 'v=0\r\no=- 1 IN IP4 0.0.0.0', targetHost: '203.0.113.7', targetPort: 443 },
    { kind: 'answer', sdp: 'v=0\r\no=- 2 IN IP4 0.0.0.0' },
    { kind: 'ice', candidate: 'candidate:1 1 UDP 12345 1.2.3.4 5000 typ host', sdpMid: '0' },
  ])('round-trips %o', (envelope) => {
    expect(decodeSignal(encodeSignal(envelope))).toEqual(envelope);
  });

  it('rejects a payload missing "kind"', () => {
    expect(() => decodeSignal(Buffer.from('{}'))).toThrow();
  });

  it('rejects an unknown "kind"', () => {
    expect(() => decodeSignal(Buffer.from(JSON.stringify({ kind: 'bogus' })))).toThrow();
  });

  it('rejects an "offer" missing targetPort', () => {
    expect(() => decodeSignal(Buffer.from(JSON.stringify({ kind: 'offer', sdp: 'x', targetHost: 'h' })))).toThrow();
  });

  it('rejects malformed JSON entirely', () => {
    expect(() => decodeSignal(Buffer.from('not json'))).toThrow();
  });

  it('accepts a Uint8Array the same as a Buffer', () => {
    const envelope: SignalEnvelope = { kind: 'answer', sdp: 'x' };
    const encoded = new Uint8Array(encodeSignal(envelope));
    expect(decodeSignal(encoded)).toEqual(envelope);
  });
});
