/**
 * Wire format for the opaque bytes carried in P2pSignal.payload (proto/vpn/
 * agent/v1/agent.proto §8.1) — this contract is entirely between a
 * connecting client and a relay peer; the server never parses it. Fixed
 * here identically across the Desktop/Android/Linux-agent relay
 * implementations (all built independently, in parallel) so they interoperate
 * regardless of which one originates a session.
 */
export type SignalEnvelope =
  | { kind: 'offer'; sdp: string; targetHost: string; targetPort: number }
  | { kind: 'answer'; sdp: string }
  | { kind: 'ice'; candidate: string; sdpMid: string };

export function encodeSignal(envelope: SignalEnvelope): Buffer {
  return Buffer.from(JSON.stringify(envelope), 'utf-8');
}

export function decodeSignal(payload: Buffer | Uint8Array): SignalEnvelope {
  const parsed = JSON.parse(Buffer.from(payload).toString('utf-8'));
  if (!parsed || typeof parsed.kind !== 'string') {
    throw new Error('Malformed P2P signal envelope: missing "kind"');
  }
  switch (parsed.kind) {
    case 'offer':
      if (typeof parsed.sdp !== 'string' || typeof parsed.targetHost !== 'string' || typeof parsed.targetPort !== 'number') {
        throw new Error('Malformed P2P "offer" envelope');
      }
      return { kind: 'offer', sdp: parsed.sdp, targetHost: parsed.targetHost, targetPort: parsed.targetPort };
    case 'answer':
      if (typeof parsed.sdp !== 'string') throw new Error('Malformed P2P "answer" envelope');
      return { kind: 'answer', sdp: parsed.sdp };
    case 'ice':
      if (typeof parsed.candidate !== 'string' || typeof parsed.sdpMid !== 'string') {
        throw new Error('Malformed P2P "ice" envelope');
      }
      return { kind: 'ice', candidate: parsed.candidate, sdpMid: parsed.sdpMid };
    default:
      throw new Error(`Unknown P2P signal kind: ${parsed.kind}`);
  }
}
