import net from 'node:net';
import { randomUUID } from 'node:crypto';
import { PeerConnection, type DataChannel, type DescriptionType } from 'node-datachannel';
import { decodeSignal, encodeSignal, type SignalEnvelope } from './signalEnvelope';

/**
 * The connecting half of P2P relaying — the piece that was missing while the
 * relaying half (relayAgent.ts here, RelaySession in the node agent) already
 * existed on every platform. Until now nothing could actually *use* a relay,
 * which is why the server refused to hand p2p nodes out at all.
 *
 * What a relay is: not an exit and not a region, but a way to reach a VPN node
 * when dialing it directly does not work. The relay opens a plain TCP
 * connection to the address this client names and pipes opaque bytes to it
 * over a WebRTC DataChannel; the VLESS/Reality session inside those bytes is
 * negotiated end-to-end between the local xray-core and the real node, so the
 * relay — somebody else's laptop — can neither read the traffic nor tell where
 * it ends up beyond that one address.
 *
 * This class turns that into something xray can use: a local TCP listener
 * where every accepted connection becomes its own relayed session, so pointing
 * an outbound at 127.0.0.1:<port> makes the hop transparent.
 */

/** The four calls this needs from the API client, kept narrow so tests need no HTTP. */
export interface RelaySignalingApi {
  /** POST /api/v1/user/p2p/nodes/{nodeId}/signal — rejects when the relay is unreachable. */
  sendP2pSignal(nodeId: number, sessionId: string, payloadBase64: string): Promise<void>;
  /** GET /api/v1/user/p2p/sessions/{id}/signals — the relay's next signal, or null if none arrived in time. */
  pollP2pSignals(sessionId: string, waitMs: number): Promise<string | null>;
  /** DELETE /api/v1/user/p2p/sessions/{id} — frees the broker's mailbox. */
  closeP2pSession(sessionId: string): Promise<void>;
  /** POST /api/v1/user/p2p/sessions/{id}/traffic-report — the client half of the dual accounting. */
  reportP2pSessionTraffic(sessionId: string, nodeId: number, bytesRelayed: number): Promise<void>;
}

export interface RelayTarget {
  host: string;
  port: number;
}

export interface P2pRelayBridgeOptions {
  /** How long a single negotiation may take before the session is abandoned. */
  negotiationTimeoutMs?: number;
  /** How long each long-poll waits server-side. */
  pollWaitMs?: number;
  iceServers?: string[];
}

const DEFAULT_NEGOTIATION_TIMEOUT_MS = 20_000;
const DEFAULT_POLL_WAIT_MS = 5_000;
const TRAFFIC_REPORT_INTERVAL_MS = 30_000;
// Stop reading from the local socket while this much is already queued on the
// channel: without it a fast local writer (xray pushing a download) outruns the
// DataChannel and the buffer grows until the process is out of memory.
const MAX_BUFFERED_BYTES = 1024 * 1024;

/** STUN only, no TURN — the same tradeoff the relaying half documents (docs §8.1). */
const DEFAULT_ICE_SERVERS = ['stun:stun.l.google.com:19302'];

interface Session {
  id: string;
  peer: PeerConnection;
  channel: DataChannel | null;
  /** Set when the channel actually opens — creating it happens immediately, opening is what takes time. */
  opened: boolean;
  socket: net.Socket;
  bytesRelayed: number;
  reportTimer: ReturnType<typeof setInterval> | null;
  pollAbort: { stopped: boolean };
  negotiationTimeout: ReturnType<typeof setTimeout> | undefined;
  closed: boolean;
}

export class P2pRelayBridge {
  private server: net.Server | null = null;
  private readonly sessions = new Set<Session>();
  private readonly options: Required<P2pRelayBridgeOptions>;

  constructor(
    private readonly api: RelaySignalingApi,
    private readonly relayNodeId: number,
    private readonly target: RelayTarget,
    options: P2pRelayBridgeOptions = {}
  ) {
    this.options = {
      negotiationTimeoutMs: options.negotiationTimeoutMs ?? DEFAULT_NEGOTIATION_TIMEOUT_MS,
      pollWaitMs: options.pollWaitMs ?? DEFAULT_POLL_WAIT_MS,
      iceServers: options.iceServers ?? DEFAULT_ICE_SERVERS,
    };
  }

  /** Starts listening on loopback. Resolves with the port to point an outbound at. */
  start(): Promise<number> {
    if (this.server) {
      throw new Error('P2P relay bridge is already started');
    }
    const server = net.createServer((socket) => void this.openSession(socket));
    this.server = server;

    return new Promise((resolve, reject) => {
      server.once('error', reject);
      // Loopback only: this port is a private hop for the local xray, not a
      // proxy for the network the machine happens to be on.
      server.listen(0, '127.0.0.1', () => {
        const address = server.address();
        if (typeof address === 'string' || address === null) {
          reject(new Error('P2P relay bridge did not get a TCP port'));
          return;
        }
        resolve(address.port);
      });
    });
  }

  async stop(): Promise<void> {
    const server = this.server;
    this.server = null;
    for (const session of [...this.sessions]) {
      this.closeSession(session);
    }
    if (!server) return;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  get activeSessions(): number {
    return this.sessions.size;
  }

  private async openSession(socket: net.Socket): Promise<void> {
    const session: Session = {
      id: randomUUID(),
      peer: new PeerConnection(`p2p-client-${Date.now()}`, { iceServers: this.options.iceServers }),
      channel: null,
      opened: false,
      socket,
      bytesRelayed: 0,
      reportTimer: null,
      pollAbort: { stopped: false },
      negotiationTimeout: undefined,
      closed: false,
    };
    this.sessions.add(session);

    // Nothing may be read off the local socket until the far end has a TCP
    // connection to write it to: the relay only dials the target once the
    // channel opens, and bytes sent before that are dropped on its side.
    socket.pause();
    socket.on('error', () => this.closeSession(session));
    socket.on('close', () => this.closeSession(session));

    // Guards the *negotiation*, so it has to test whether the channel opened,
    // not whether it exists: createDataChannel returns one instantly, and
    // checking that instead would never fire — a relay that answers nothing
    // would hold the session (and the user's connection attempt) forever.
    const timeout = setTimeout(() => {
      if (!session.opened) this.closeSession(session);
    }, this.options.negotiationTimeoutMs);
    session.negotiationTimeout = timeout;

    session.peer.onLocalDescription((sdp, type) => {
      if (type !== 'offer') return;
      void this.send(session, { kind: 'offer', sdp, targetHost: this.target.host, targetPort: this.target.port });
    });
    session.peer.onLocalCandidate((candidate, mid) => {
      void this.send(session, { kind: 'ice', candidate, sdpMid: mid });
    });
    session.peer.onStateChange((state) => {
      if (state === 'closed' || state === 'failed' || state === 'disconnected') {
        this.closeSession(session);
      }
    });

    // Creating the channel is what makes this side the offerer; the relay
    // answers and picks it up through its own onDataChannel.
    const channel = session.peer.createDataChannel('relay');
    session.channel = channel;

    channel.onOpen(() => {
      session.opened = true;
      clearTimeout(timeout);
      this.bridge(session, channel);
    });
    channel.onClosed(() => this.closeSession(session));

    void this.pollLoop(session);
  }

  /** Pipes the local socket and the channel into each other, counting bytes for the accounting. */
  private bridge(session: Session, channel: DataChannel): void {
    const { socket } = session;

    socket.on('data', (chunk: Buffer) => {
      if (session.closed) return;
      try {
        channel.sendMessageBinary(chunk);
        session.bytesRelayed += chunk.length;
      } catch {
        this.closeSession(session);
        return;
      }
      // Backpressure: stop reading while the channel is behind, resume once it
      // has drained. bufferedAmount is not in every build of the binding, so
      // its absence simply means no throttling rather than a crash.
      const buffered = typeof channel.bufferedAmount === 'function' ? channel.bufferedAmount() : 0;
      if (buffered > MAX_BUFFERED_BYTES) {
        socket.pause();
        if (typeof channel.onBufferedAmountLow === 'function') {
          channel.onBufferedAmountLow(() => socket.resume());
          if (typeof channel.setBufferedAmountLowThreshold === 'function') {
            channel.setBufferedAmountLowThreshold(MAX_BUFFERED_BYTES / 2);
          }
        } else {
          setTimeout(() => socket.resume(), 50);
        }
      }
    });

    channel.onMessage((message) => {
      if (session.closed) return;
      const buffer = typeof message === 'string' ? Buffer.from(message, 'utf-8') : Buffer.from(message);
      session.bytesRelayed += buffer.length;
      session.socket.write(buffer);
    });

    session.reportTimer = setInterval(() => void this.reportTraffic(session), TRAFFIC_REPORT_INTERVAL_MS);
    socket.resume();
  }

  /**
   * Collects what the relay says until the channel is open (or the session
   * ends). The broker queues those signals, so nothing is lost between polls —
   * which matters because the answer is followed by a burst of ICE candidates,
   * and a connection through a NAT depends on them.
   */
  private async pollLoop(session: Session): Promise<void> {
    while (!session.closed && !session.pollAbort.stopped) {
      let payload: string | null = null;
      try {
        payload = await this.api.pollP2pSignals(session.id, this.options.pollWaitMs);
      } catch {
        // The negotiation timeout is what ends this; a single failed poll
        // (a dropped connection, a restarting server) is worth one retry.
        if (session.closed) return;
        await new Promise((resolve) => setTimeout(resolve, 500));
        continue;
      }
      if (session.closed) return;
      if (!payload) continue;

      try {
        const envelope = decodeSignal(Buffer.from(payload, 'base64'));
        if (envelope.kind === 'answer') {
          session.peer.setRemoteDescription(envelope.sdp, 'answer' as DescriptionType);
        } else if (envelope.kind === 'ice') {
          session.peer.addRemoteCandidate(envelope.candidate, envelope.sdpMid);
        }
      } catch {
        // A malformed signal is the relay's problem, not a reason to tear down
        // a session that may still complete on the candidates already applied.
      }
    }
  }

  private async send(session: Session, envelope: SignalEnvelope): Promise<void> {
    if (session.closed) return;
    try {
      await this.api.sendP2pSignal(this.relayNodeId, session.id, encodeSignal(envelope).toString('base64'));
    } catch {
      // An offer that cannot be delivered means this relay is gone; the caller
      // retries with another one rather than this session limping on.
      this.closeSession(session);
    }
  }

  private async reportTraffic(session: Session): Promise<void> {
    if (session.bytesRelayed <= 0) return;
    try {
      await this.api.reportP2pSessionTraffic(session.id, this.relayNodeId, session.bytesRelayed);
    } catch {
      // Best-effort: the relay's own half of the report is what actually pays
      // its owner, and a missed client report only costs that one credit.
    }
  }

  private closeSession(session: Session): void {
    if (session.closed) return;
    session.closed = true;
    session.pollAbort.stopped = true;
    this.sessions.delete(session);

    clearTimeout(session.negotiationTimeout);
    if (session.reportTimer) {
      clearInterval(session.reportTimer);
      session.reportTimer = null;
    }
    // Final report first: a short session that never reached a periodic tick
    // would otherwise be relayed for free.
    void this.reportTraffic(session);
    void this.api.closeP2pSession(session.id).catch(() => undefined);

    try {
      session.socket.destroy();
    } catch {
      // already gone
    }
    try {
      session.channel?.close();
    } catch {
      // already closing
    }
    try {
      session.peer.close();
    } catch {
      // already closing
    }
  }
}
