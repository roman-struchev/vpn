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
  /**
   * POST /api/v1/user/p2p/sessions/{id}/traffic-report — the client half of
   * the dual accounting. {@code exit} says the peer was used as an exit, which
   * is what decides whether these bytes are charged to this account's own
   * quota (nothing else meters them — no node of ours was in the path).
   */
  reportP2pSessionTraffic(sessionId: string, nodeId: number, bytesRelayed: number, exit: boolean): Promise<void>;
}

export interface RelayTarget {
  host: string;
  port: number;
}

/**
 * What the far end should be told to connect to, which is the whole difference
 * between the two ways a peer is used (see P2pRelayDirectory on the server):
 *
 * - a {@link RelayTarget} — every session goes to that one address, one of our
 *   nodes, and the peer is a *path* to it. The tunnel stays end-to-end with
 *   the node.
 * - `'socks'` — the bridge speaks SOCKS5 to whatever connects to it and each
 *   session goes wherever that connection asked, so the peer is the *exit*:
 *   the traffic reaches the internet from their machine, under their IP, with
 *   no node of ours involved.
 */
export type BridgeMode = RelayTarget | 'socks';

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
  /** Sent once the channel opens, in SOCKS5 mode — see socksReply. */
  pendingSocksReply: boolean;
}

export class P2pRelayBridge {
  private server: net.Server | null = null;
  private readonly sessions = new Set<Session>();
  private readonly options: Required<P2pRelayBridgeOptions>;

  constructor(
    private readonly api: RelaySignalingApi,
    private readonly relayNodeId: number,
    private readonly mode: BridgeMode,
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
    // Nothing may be read off the local socket until the far end has a TCP
    // connection to write it to: the peer only dials the target once the
    // channel opens, and bytes sent before that are dropped on its side.
    // Pausing first also puts the socket in the paused mode the SOCKS5
    // handshake reads in, so it can take exactly the handshake bytes and
    // leave the payload that follows untouched in the buffer.
    socket.pause();

    let target: RelayTarget;
    if (this.mode === 'socks') {
      try {
        target = await readSocksRequest(socket, this.options.negotiationTimeoutMs);
      } catch {
        // A local client that cannot speak SOCKS5 to us is a bug on our own
        // side (we point xray at this port), not something to keep alive.
        socket.destroy();
        return;
      }
    } else {
      target = this.mode;
    }

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
      pendingSocksReply: this.mode === 'socks',
    };
    this.sessions.add(session);

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
      void this.send(session, { kind: 'offer', sdp, targetHost: target.host, targetPort: target.port });
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
      // Answered only now, not when the request was parsed: an early success
      // would have xray believe it has a working connection while the
      // negotiation is still running (or about to fail), turning every
      // unreachable peer into a hung request instead of a fast retry.
      if (session.pendingSocksReply) {
        session.pendingSocksReply = false;
        session.socket.write(SOCKS5_SUCCESS_REPLY);
      }
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
      await this.api.reportP2pSessionTraffic(
        session.id,
        this.relayNodeId,
        session.bytesRelayed,
        this.mode === 'socks'
      );
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

/**
 * SOCKS5, enough of it to be the local end of an exit hop (RFC 1928).
 *
 * Only what xray's own socks outbound sends us, and deliberately no more: no
 * authentication (this listener is on loopback and is reached only by the
 * xray process we start ourselves), no BIND, no UDP ASSOCIATE — a peer
 * forwards a TCP stream and nothing else, which is why the exit config routes
 * DNS over DoH rather than letting UDP:53 reach here (see xrayConfigFactory).
 */
const SOCKS5_VERSION = 0x05;
const SOCKS5_CMD_CONNECT = 0x01;
const SOCKS5_ATYP_IPV4 = 0x01;
const SOCKS5_ATYP_DOMAIN = 0x03;
const SOCKS5_ATYP_IPV6 = 0x04;
const SOCKS5_NO_AUTH = 0x00;

/**
 * "Succeeded", with 0.0.0.0:0 as the bound address. The real bound address is
 * the peer's, and we never learn it — nor should the local client care: it
 * asked for a stream to a destination and it is getting one.
 */
const SOCKS5_SUCCESS_REPLY = Buffer.from([SOCKS5_VERSION, 0x00, 0x00, SOCKS5_ATYP_IPV4, 0, 0, 0, 0, 0, 0]);

/** Reads the greeting and the CONNECT request, answers the greeting, and returns where the caller wants to go. */
async function readSocksRequest(socket: net.Socket, timeoutMs: number): Promise<RelayTarget> {
  const deadline = Date.now() + timeoutMs;
  const read = (n: number) => readExactly(socket, n, Math.max(1, deadline - Date.now()));

  const [version, methodCount] = await read(2);
  if (version !== SOCKS5_VERSION) {
    throw new Error(`socks: unsupported version ${version}`);
  }
  await read(methodCount); // the offered methods, none of which we need to look at
  socket.write(Buffer.from([SOCKS5_VERSION, SOCKS5_NO_AUTH]));

  const header = await read(4);
  if (header[0] !== SOCKS5_VERSION) {
    throw new Error(`socks: unsupported version ${header[0]} in request`);
  }
  if (header[1] !== SOCKS5_CMD_CONNECT) {
    // 0x07: command not supported. Answered so the local client fails cleanly
    // instead of waiting on a reply that never comes.
    socket.write(Buffer.from([SOCKS5_VERSION, 0x07, 0x00, SOCKS5_ATYP_IPV4, 0, 0, 0, 0, 0, 0]));
    throw new Error(`socks: unsupported command ${header[1]}`);
  }

  let host: string;
  switch (header[3]) {
    case SOCKS5_ATYP_IPV4:
      host = Array.from(await read(4)).join('.');
      break;
    case SOCKS5_ATYP_DOMAIN: {
      const [length] = await read(1);
      host = (await read(length)).toString('utf-8');
      break;
    }
    case SOCKS5_ATYP_IPV6: {
      const raw = await read(16);
      const groups: string[] = [];
      for (let i = 0; i < 16; i += 2) {
        groups.push(raw.readUInt16BE(i).toString(16));
      }
      host = groups.join(':');
      break;
    }
    default:
      socket.write(Buffer.from([SOCKS5_VERSION, 0x08, 0x00, SOCKS5_ATYP_IPV4, 0, 0, 0, 0, 0, 0]));
      throw new Error(`socks: unsupported address type ${header[3]}`);
  }

  const port = (await read(2)).readUInt16BE(0);
  return { host, port };
}

/**
 * Exactly {@code n} bytes off a *paused* socket, leaving anything beyond them
 * in the buffer — which is what keeps the first bytes of the payload from
 * being swallowed with the handshake.
 */
function readExactly(socket: net.Socket, n: number, timeoutMs: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    if (n === 0) {
      resolve(Buffer.alloc(0));
      return;
    }

    const attempt = () => {
      const chunk = socket.read(n) as Buffer | null;
      if (chunk) {
        cleanup();
        resolve(chunk);
      }
    };
    const fail = () => {
      cleanup();
      reject(new Error('socks: handshake did not complete'));
    };
    const timer = setTimeout(fail, timeoutMs);
    const cleanup = () => {
      clearTimeout(timer);
      socket.off('readable', attempt);
      socket.off('end', fail);
      socket.off('error', fail);
    };

    socket.on('readable', attempt);
    socket.on('end', fail);
    socket.on('error', fail);
    attempt();
  });
}
