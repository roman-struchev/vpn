import { describe, it, expect, afterEach, vi } from 'vitest';
import net from 'node:net';
import { PeerConnection, type DataChannel } from 'node-datachannel';
import { P2pRelayBridge, type RelaySignalingApi } from '../src/main/p2p/relayClient';
import { decodeSignal, encodeSignal, type SignalEnvelope } from '../src/main/p2p/signalEnvelope';

/**
 * End-to-end over real WebRTC: the bridge under test negotiates with a stand-in
 * relay built the same way the real ones are (agent's RelaySession, desktop's
 * relayAgent — answerer, ACL-checked TCP dial, opaque byte pipe), through a
 * signaling API that queues envelopes exactly as the server's broker does.
 *
 * Nothing here is mocked at the protocol level: a data channel really opens
 * over loopback ICE, and the bytes really come back off a TCP server. That is
 * the point — the parts worth being sure about are precisely the ones a mock
 * would paper over.
 */

/** In-process stand-in for the server broker: queues per direction, never inspects payloads. */
class FakeBroker implements RelaySignalingApi {
  private readonly toClient: string[] = [];
  private readonly toRelay: string[] = [];
  readonly trafficReports: { sessionId: string; nodeId: number; bytes: number }[] = [];
  closedSessions: string[] = [];
  failSend = false;

  async sendP2pSignal(_nodeId: number, _sessionId: string, payloadBase64: string): Promise<void> {
    if (this.failSend) throw new Error('relay unreachable');
    this.toRelay.push(payloadBase64);
  }

  async pollP2pSignals(_sessionId: string, waitMs: number): Promise<string | null> {
    const deadline = Date.now() + Math.min(waitMs, 3000);
    while (Date.now() < deadline) {
      const next = this.toClient.shift();
      if (next) return next;
      await new Promise((r) => setTimeout(r, 10));
    }
    return null;
  }

  async closeP2pSession(sessionId: string): Promise<void> {
    this.closedSessions.push(sessionId);
  }

  async reportP2pSessionTraffic(sessionId: string, nodeId: number, bytesRelayed: number): Promise<void> {
    this.trafficReports.push({ sessionId, nodeId, bytes: bytesRelayed });
  }

  /** The relay side of the broker. */
  pushToClient(envelope: SignalEnvelope): void {
    this.toClient.push(encodeSignal(envelope).toString('base64'));
  }

  async takeFromClient(timeoutMs = 3000): Promise<SignalEnvelope | null> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const next = this.toRelay.shift();
      if (next) return decodeSignal(Buffer.from(next, 'base64'));
      await new Promise((r) => setTimeout(r, 10));
    }
    return null;
  }
}

/** A relay peer, doing what the real ones do: answer, dial the named target, pipe bytes. */
class StubRelay {
  private peer: PeerConnection | null = null;
  private channel: DataChannel | null = null;
  private socket: net.Socket | null = null;
  dialedTarget: { host: string; port: number } | null = null;

  constructor(private readonly broker: FakeBroker) {}

  async run(): Promise<void> {
    const offer = await this.broker.takeFromClient();
    if (!offer || offer.kind !== 'offer') throw new Error(`expected an offer, got ${offer?.kind}`);
    this.dialedTarget = { host: offer.targetHost, port: offer.targetPort };

    const peer = new PeerConnection('stub-relay', { iceServers: [] });
    this.peer = peer;

    peer.onLocalDescription((sdp, type) => {
      if (type === 'answer') this.broker.pushToClient({ kind: 'answer', sdp });
    });
    peer.onLocalCandidate((candidate, mid) => {
      this.broker.pushToClient({ kind: 'ice', candidate, sdpMid: mid });
    });
    peer.onDataChannel((dc) => {
      this.channel = dc;
      const socket = net.createConnection({ host: offer.targetHost, port: offer.targetPort });
      this.socket = socket;
      socket.on('data', (chunk) => {
        try {
          dc.sendMessageBinary(chunk);
        } catch {
          /* closing */
        }
      });
      dc.onMessage((msg) => {
        socket.write(typeof msg === 'string' ? Buffer.from(msg, 'utf-8') : Buffer.from(msg));
      });
    });

    peer.setRemoteDescription(offer.sdp, 'offer');

    // Trickle whatever the client sends afterwards for as long as it keeps coming.
    void (async () => {
      for (;;) {
        const envelope = await this.broker.takeFromClient(1500);
        if (!envelope) return;
        if (envelope.kind === 'ice') {
          try {
            peer.addRemoteCandidate(envelope.candidate, envelope.sdpMid);
          } catch {
            /* late candidate */
          }
        }
      }
    })();
  }

  close(): void {
    try {
      this.socket?.destroy();
    } catch {
      /* ignore */
    }
    try {
      this.channel?.close();
    } catch {
      /* ignore */
    }
    try {
      this.peer?.close();
    } catch {
      /* ignore */
    }
  }
}

/** Stands in for the VPN node the relay is asked to reach. */
function startEchoServer(transform: (chunk: Buffer) => Buffer = (c) => c): Promise<{ port: number; close: () => void; received: Buffer[] }> {
  const received: Buffer[] = [];
  const server = net.createServer((socket) => {
    socket.on('data', (chunk) => {
      received.push(chunk);
      socket.write(transform(chunk));
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address() as net.AddressInfo;
      resolve({ port: address.port, close: () => server.close(), received });
    });
  });
}

describe('P2pRelayBridge (connecting half of P2P relaying)', () => {
  const cleanups: (() => void)[] = [];

  afterEach(() => {
    while (cleanups.length) cleanups.pop()!();
  });

  it('carries bytes both ways through a real data channel to the named target', async () => {
    const echo = await startEchoServer((chunk) => Buffer.from(chunk.toString('utf-8').toUpperCase()));
    cleanups.push(echo.close);

    const broker = new FakeBroker();
    const relay = new StubRelay(broker);
    cleanups.push(() => relay.close());

    const bridge = new P2pRelayBridge(broker, 42, { host: '127.0.0.1', port: echo.port }, { pollWaitMs: 500 });
    cleanups.push(() => void bridge.stop());
    const localPort = await bridge.start();

    const client = net.createConnection({ host: '127.0.0.1', port: localPort });
    const replies: Buffer[] = [];
    client.on('data', (chunk) => replies.push(chunk));

    await relay.run();

    client.write('hello relay');
    await vi.waitFor(() => expect(Buffer.concat(replies).toString()).toBe('HELLO RELAY'), { timeout: 15000 });

    // The relay dialed what the client asked for — a relay never chooses the
    // destination itself.
    expect(relay.dialedTarget).toEqual({ host: '127.0.0.1', port: echo.port });
    expect(echo.received.length).toBeGreaterThan(0);
    client.destroy();
  }, 30000);

  it('reports what it relayed, so the relay owner gets credited', async () => {
    const echo = await startEchoServer();
    cleanups.push(echo.close);

    const broker = new FakeBroker();
    const relay = new StubRelay(broker);
    cleanups.push(() => relay.close());

    const bridge = new P2pRelayBridge(broker, 77, { host: '127.0.0.1', port: echo.port }, { pollWaitMs: 500 });
    const localPort = await bridge.start();
    const client = net.createConnection({ host: '127.0.0.1', port: localPort });
    await relay.run();
    client.write('0123456789');
    await vi.waitFor(() => expect(broker.trafficReports.length).toBe(0), { timeout: 100 }).catch(() => undefined);

    client.destroy();
    await vi.waitFor(() => expect(broker.trafficReports.length).toBeGreaterThan(0), { timeout: 15000 });

    const report = broker.trafficReports.at(-1)!;
    expect(report.nodeId).toBe(77);
    expect(report.bytes).toBeGreaterThan(0);
    await bridge.stop();
  }, 30000);

  it('gives up on a session whose relay never answers, without leaking it', async () => {
    const broker = new FakeBroker();
    const bridge = new P2pRelayBridge(broker, 5, { host: '127.0.0.1', port: 1 }, {
      negotiationTimeoutMs: 700,
      pollWaitMs: 200,
    });
    const localPort = await bridge.start();

    const client = net.createConnection({ host: '127.0.0.1', port: localPort });
    await vi.waitFor(() => expect(bridge.activeSessions).toBe(1), { timeout: 5000 });

    await vi.waitFor(() => expect(bridge.activeSessions).toBe(0), { timeout: 10000 });
    expect(broker.closedSessions.length).toBe(1);
    client.destroy();
    await bridge.stop();
  }, 20000);

  it('drops a session immediately when the relay cannot be signalled at all', async () => {
    const broker = new FakeBroker();
    broker.failSend = true;
    const bridge = new P2pRelayBridge(broker, 5, { host: '127.0.0.1', port: 1 }, { pollWaitMs: 200 });
    const localPort = await bridge.start();

    const client = net.createConnection({ host: '127.0.0.1', port: localPort });
    await vi.waitFor(() => expect(bridge.activeSessions).toBe(0), { timeout: 10000 });

    client.destroy();
    await bridge.stop();
  }, 20000);

  it('stops cleanly, closing every session it still holds', async () => {
    const echo = await startEchoServer();
    cleanups.push(echo.close);

    const broker = new FakeBroker();
    const relay = new StubRelay(broker);
    cleanups.push(() => relay.close());

    const bridge = new P2pRelayBridge(broker, 9, { host: '127.0.0.1', port: echo.port }, { pollWaitMs: 500 });
    const localPort = await bridge.start();
    const client = net.createConnection({ host: '127.0.0.1', port: localPort });
    await relay.run();
    await vi.waitFor(() => expect(bridge.activeSessions).toBe(1), { timeout: 15000 });

    await bridge.stop();

    expect(bridge.activeSessions).toBe(0);
    expect(broker.closedSessions.length).toBeGreaterThan(0);
    client.destroy();
  }, 30000);
});
