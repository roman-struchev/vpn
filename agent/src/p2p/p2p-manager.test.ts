import { describe, expect, it, vi } from 'vitest';
import { P2pManager, type SessionFactory } from './p2p-manager.js';
import type { SignalEnvelope } from './relay-session.js';

/**
 * Covers P2pManager's own routing/lifecycle responsibility — lazy session
 * creation, rejecting a non-"offer" first message, routing subsequent
 * signals to an existing session, and cleanup — independent of
 * RelaySession's real WebRTC behavior (already covered by
 * relay-session.test.ts) via an injected fake SessionFactory. Nothing here
 * opens a real PeerConnection, so these run fast and deterministically.
 */
describe('P2pManager', () => {
  function fakeFactory() {
    const created: { sessionId: string; onClose: () => void; handleSignal: ReturnType<typeof vi.fn> }[] = [];
    const factory: SessionFactory = (sessionId, _sendSignal, _reportTraffic, onClose) => {
      const handleSignal = vi.fn(async (_envelope: SignalEnvelope) => undefined);
      created.push({ sessionId, onClose, handleSignal });
      return { handleSignal, close: vi.fn() };
    };
    return { factory, created };
  }

  function offerPayload(targetHost = '203.0.113.7', targetPort = 443): Buffer {
    return Buffer.from(JSON.stringify({ kind: 'offer', sdp: 'v=0...', targetHost, targetPort }), 'utf-8');
  }

  it('creates a new session lazily on the first "offer" signal', async () => {
    const { factory, created } = fakeFactory();
    const manager = new P2pManager({ sendSignalToServer: vi.fn(), reportTraffic: vi.fn() }, factory);

    await manager.handleIncomingSignal('sess-1', offerPayload());

    expect(created).toHaveLength(1);
    expect(created[0].sessionId).toBe('sess-1');
    expect(manager.activeSessionCount).toBe(1);
  });

  it('holds an ICE candidate that overtakes its offer, and applies it right after the offer', async () => {
    // A client fires its offer and candidates as separate requests at once,
    // so a candidate routinely arrives first; dropping it lost exactly the
    // candidates NAT traversal needs.
    const { factory, created } = fakeFactory();
    const manager = new P2pManager({ sendSignalToServer: vi.fn(), reportTraffic: vi.fn() }, factory);

    const icePayload = Buffer.from(JSON.stringify({ kind: 'ice', candidate: 'candidate:1...', sdpMid: '0' }), 'utf-8');
    await manager.handleIncomingSignal('sess-2', icePayload);
    expect(created).toHaveLength(0);
    expect(manager.activeSessionCount).toBe(0);

    const offerPayload = Buffer.from(
      JSON.stringify({ kind: 'offer', sdp: 'v=0...', targetHost: '203.0.113.5', targetPort: 443 }),
      'utf-8'
    );
    await manager.handleIncomingSignal('sess-2', offerPayload);

    expect(created).toHaveLength(1);
    const kinds = created[0].handleSignal.mock.calls.map((c: unknown[]) => (c[0] as { kind: string }).kind);
    expect(kinds).toEqual(['offer', 'ice']);
  });

  it('never creates a session from ICE alone', async () => {
    const { factory, created } = fakeFactory();
    const manager = new P2pManager({ sendSignalToServer: vi.fn(), reportTraffic: vi.fn() }, factory);
    for (let i = 0; i < 100; i++) {
      await manager.handleIncomingSignal(
        'sess-ice-only',
        Buffer.from(JSON.stringify({ kind: 'ice', candidate: `candidate:${i}`, sdpMid: '0' }), 'utf-8')
      );
    }
    expect(created).toHaveLength(0);
  });

  it('routes a second signal for the same session_id to the existing session, not a new one', async () => {
    const { factory, created } = fakeFactory();
    const manager = new P2pManager({ sendSignalToServer: vi.fn(), reportTraffic: vi.fn() }, factory);

    await manager.handleIncomingSignal('sess-3', offerPayload());
    const icePayload = Buffer.from(JSON.stringify({ kind: 'ice', candidate: 'candidate:2...', sdpMid: '0' }), 'utf-8');
    await manager.handleIncomingSignal('sess-3', icePayload);

    expect(created).toHaveLength(1); // still just the one session
    expect(created[0].handleSignal).toHaveBeenCalledTimes(2);
    expect(created[0].handleSignal.mock.calls[1][0]).toMatchObject({ kind: 'ice' });
  });

  it('drops a malformed (non-JSON) payload without creating a session or throwing', async () => {
    const { factory, created } = fakeFactory();
    const manager = new P2pManager({ sendSignalToServer: vi.fn(), reportTraffic: vi.fn() }, factory);

    await expect(manager.handleIncomingSignal('sess-4', Buffer.from('not json', 'utf-8'))).resolves.toBeUndefined();
    expect(created).toHaveLength(0);
  });

  it('removes a session from the active map once its onClose callback fires', async () => {
    const { factory, created } = fakeFactory();
    const manager = new P2pManager({ sendSignalToServer: vi.fn(), reportTraffic: vi.fn() }, factory);

    await manager.handleIncomingSignal('sess-5', offerPayload());
    expect(manager.activeSessionCount).toBe(1);

    created[0].onClose();
    expect(manager.activeSessionCount).toBe(0);
  });

  it('keeps two concurrent sessions independent of each other', async () => {
    const { factory, created } = fakeFactory();
    const manager = new P2pManager({ sendSignalToServer: vi.fn(), reportTraffic: vi.fn() }, factory);

    await manager.handleIncomingSignal('sess-a', offerPayload('203.0.113.1', 443));
    await manager.handleIncomingSignal('sess-b', offerPayload('203.0.113.2', 8443));

    expect(created).toHaveLength(2);
    expect(manager.activeSessionCount).toBe(2);
  });

  it('shutdown() closes every active session and clears the map', async () => {
    const { factory, created } = fakeFactory();
    const manager = new P2pManager({ sendSignalToServer: vi.fn(), reportTraffic: vi.fn() }, factory);

    await manager.handleIncomingSignal('sess-x', offerPayload());
    await manager.handleIncomingSignal('sess-y', offerPayload());

    manager.shutdown();

    expect(manager.activeSessionCount).toBe(0);
  });
});
