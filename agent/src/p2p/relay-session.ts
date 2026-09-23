import nodeDataChannel, { type PeerConnection, type DataChannel } from 'node-datachannel';
import net from 'net';
import { logger } from '../utils/logger.js';
import { checkDestination, LookupFn } from './dest-acl.js';

// This relay agent deliberately never runs Xray-core for a p2p session (see
// docs/research/P2P_RELAY_FEASIBILITY.md §8) — it is a protocol-blind byte
// pipe between a WebRTC DataChannel and a plain TCP socket. The connecting
// client's own local xray-core is what actually speaks VLESS/Reality to the
// real destination; this relay never decrypts or understands that traffic,
// which is also why it can only ever be as trustworthy as "forwards opaque
// bytes to a destination-ACL-checked address" — nothing more.
export interface SignalEnvelope {
  kind: 'offer' | 'answer' | 'ice';
  sdp?: string;
  targetHost?: string;
  targetPort?: number;
  candidate?: string;
  sdpMid?: string;
}

export type SendSignal = (envelope: SignalEnvelope) => void;
/** Called periodically and once more on close, with the cumulative (not delta) byte count for this session so far. */
export type ReportTraffic = (bytesRelayedTotal: number) => void;
/** Opens the TCP leg of the bridge — overridable purely for tests, so the byte-forwarding logic can be exercised against a local test server without needing a real dialable target IP (production always uses the real net.createConnection). */
export type ConnectFn = (host: string, port: number) => net.Socket;

const TRAFFIC_REPORT_INTERVAL_MS = 30_000;

/**
 * One relay session's whole lifecycle: WebRTC answerer, destination-ACL gate,
 * and the TCP<->DataChannel byte bridge, keyed by a session_id minted by the
 * connecting client (opaque to this agent — see AgentStreamServiceImpl's
 * server-side routing, which never inspects it either).
 */
export class RelaySession {
  private readonly peer: PeerConnection;
  private dataChannel: DataChannel | null = null;
  private socket: net.Socket | null = null;
  private targetIp: string | null = null;
  private targetPort: number | null = null;
  private bytesRelayed = 0;
  private closed = false;
  private reportTimer: ReturnType<typeof setInterval> | null = null;

  constructor(
    public readonly sessionId: string,
    private readonly sendSignal: SendSignal,
    private readonly reportTraffic: ReportTraffic,
    private readonly onClose?: () => void,
    private readonly lookup?: LookupFn,
    private readonly connect: ConnectFn = (host, port) => net.createConnection({ host, port })
  ) {
    this.peer = new nodeDataChannel.PeerConnection(`p2p-relay-${sessionId}`, {
      // STUN only, no TURN fallback — an accepted design tradeoff (docs §8.1):
      // some real-world NAT combinations (e.g. symmetric NAT on both sides)
      // simply won't connect. Verified in this environment (Docker, two
      // isolated bridge networks + a public STUN server) that the DataChannel
      // negotiation and open/message-exchange code path itself works
      // correctly; genuine cross-real-NAT reachability cannot be validated
      // from this single-host environment and remains a manual test item.
      iceServers: ['stun:stun.l.google.com:19302'],
    });

    this.peer.onStateChange((state: string) => {
      logger.debug(`P2P session ${sessionId}: peer connection state -> ${state}`);
      if (state === 'closed' || state === 'failed' || state === 'disconnected') {
        this.close();
      }
    });
    this.peer.onLocalDescription((sdp: string, type: string) => {
      if (type === 'answer') this.sendSignal({ kind: 'answer', sdp });
    });
    this.peer.onLocalCandidate((candidate: string, mid: string) => {
      this.sendSignal({ kind: 'ice', candidate, sdpMid: mid });
    });
    this.peer.onDataChannel((dc: DataChannel) => this.wireDataChannel(dc));

    this.reportTimer = setInterval(() => this.reportTraffic(this.bytesRelayed), TRAFFIC_REPORT_INTERVAL_MS);
  }

  /** Handles one signaling envelope addressed to this session (offer carries the ACL-checked target; ice is trickled either direction). */
  /**
   * Candidates that arrive while the offer is still being checked (the ACL
   * lookup below is async): added before the remote description they were
   * rejected and lost. Applied right after it instead.
   */
  private remoteDescriptionSet = false;
  private pendingIce: SignalEnvelope[] = [];

  async handleSignal(envelope: SignalEnvelope): Promise<void> {
    if (this.closed) return;

    if (envelope.kind === 'offer') {
      if (!envelope.sdp || !envelope.targetHost || !envelope.targetPort) {
        logger.warn(`P2P session ${this.sessionId}: malformed offer (missing sdp/targetHost/targetPort) — rejecting`);
        this.close();
        return;
      }
      const acl = await checkDestination(envelope.targetHost, this.lookup);
      if (!acl.allowed) {
        logger.warn(`P2P session ${this.sessionId}: destination "${envelope.targetHost}" rejected by ACL (${acl.reason}) — refusing session`);
        this.close();
        return;
      }
      // Connect to the resolved IP, never re-resolve targetHost later — see
      // dest-acl.ts's header comment on why that specifically prevents
      // DNS-rebinding from bypassing this check.
      this.targetIp = acl.resolvedIp!;
      this.targetPort = envelope.targetPort;
      this.peer.setRemoteDescription(envelope.sdp, 'offer');
      this.remoteDescriptionSet = true;
      const pending = this.pendingIce;
      this.pendingIce = [];
      for (const ice of pending) await this.handleSignal(ice);
    } else if (envelope.kind === 'ice') {
      if (!envelope.candidate) return;
      if (!this.remoteDescriptionSet) {
        if (this.pendingIce.length < 32) this.pendingIce.push(envelope);
        return;
      }
      try {
        this.peer.addRemoteCandidate(envelope.candidate, envelope.sdpMid || '0');
      } catch (err) {
        logger.debug(`P2P session ${this.sessionId}: failed to add remote ICE candidate: ${(err as Error).message}`);
      }
    }
  }

  private wireDataChannel(dc: DataChannel): void {
    this.dataChannel = dc;

    dc.onOpen(() => {
      if (this.closed || !this.targetIp || !this.targetPort) return;
      logger.info(`P2P session ${this.sessionId}: data channel open — bridging to ${this.targetIp}:${this.targetPort}`);
      const socket = this.connect(this.targetIp, this.targetPort);
      this.socket = socket;

      socket.on('data', (chunk: Buffer) => {
        this.bytesRelayed += chunk.length;
        try {
          dc.sendMessageBinary(chunk);
        } catch (err) {
          logger.debug(`P2P session ${this.sessionId}: failed to forward TCP->DC chunk: ${(err as Error).message}`);
        }
      });
      socket.on('error', (err) => {
        logger.warn(`P2P session ${this.sessionId}: TCP bridge error: ${err.message}`);
        this.close();
      });
      socket.on('close', () => this.close());
    });

    dc.onMessage((msg: string | Buffer | ArrayBuffer) => {
      if (!this.socket) {
        logger.debug(`P2P session ${this.sessionId}: data channel message arrived before TCP socket was ready — dropping`);
        return;
      }
      let buf: Buffer;
      if (Buffer.isBuffer(msg)) {
        buf = msg;
      } else if (typeof msg === 'string') {
        buf = Buffer.from(msg, 'binary');
      } else {
        buf = Buffer.from(msg);
      }
      this.bytesRelayed += buf.length;
      this.socket.write(buf);
    });

    dc.onClosed(() => this.close());
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.reportTimer) {
      clearInterval(this.reportTimer);
      this.reportTimer = null;
    }
    // Final report carries whatever was accumulated even if it never reached
    // a 30s boundary — a short-lived session shouldn't go entirely unreported
    // just because it ended before the first periodic tick.
    this.reportTraffic(this.bytesRelayed);
    try {
      this.socket?.destroy();
    } catch {
      // already closing
    }
    try {
      this.dataChannel?.close();
    } catch {
      // already closing
    }
    try {
      this.peer.close();
    } catch {
      // already closing
    }
    this.onClose?.();
  }
}
