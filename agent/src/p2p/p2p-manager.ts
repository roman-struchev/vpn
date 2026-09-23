import { logger } from '../utils/logger.js';
import { RelaySession, SignalEnvelope } from './relay-session.js';

export interface P2pManagerCallbacks {
  /** Sends an envelope back over this node's own gRPC stream, addressed to sessionId (AgentMessage.p2p_signal). */
  sendSignalToServer: (sessionId: string, envelope: SignalEnvelope) => void;
  /** Reports the session's cumulative (not delta) bytes relayed so far (AgentMessage.p2p_traffic_report). */
  reportTraffic: (sessionId: string, bytesRelayedTotal: number) => void;
}

// Minimal surface P2pManager actually depends on — lets a test double stand
// in for a real RelaySession (which opens a real WebRTC PeerConnection on
// construction) so the manager's own routing/lifecycle logic (lazy creation,
// rejecting a non-"offer" first message, cleanup) can be tested fast and
// deterministically, independent of RelaySession's own WebRTC behavior
// (already covered by relay-session.test.ts).
interface SessionLike {
  handleSignal(envelope: SignalEnvelope): Promise<void>;
  close(): void;
}

export type SessionFactory = (
  sessionId: string,
  sendSignal: (envelope: SignalEnvelope) => void,
  reportTraffic: (bytesRelayedTotal: number) => void,
  onClose: () => void
) => SessionLike;

const defaultSessionFactory: SessionFactory = (sessionId, sendSignal, reportTraffic, onClose) =>
  new RelaySession(sessionId, sendSignal, reportTraffic, onClose);

/**
 * Owns every concurrent RelaySession this node instance is currently
 * bridging, keyed by session_id. A session is created lazily on its first
 * signal (which must be an "offer" — the relay node never initiates a
 * session itself, see docs/research/P2P_RELAY_FEASIBILITY.md §8.1) and
 * removed once it closes, so a long-running p2p node doesn't leak memory
 * across many short-lived sessions.
 */
/**
 * ICE candidates that arrive before their session's offer. A client sends its
 * offer and its candidates as separate requests, fired together, so a
 * candidate routinely overtakes the offer on the way here; dropping it (as
 * this used to) threw away exactly the candidates NAT traversal needs.
 * Bounded per session and in total, and forgotten after a while — an offer
 * that never comes must not pin memory.
 */
const MAX_EARLY_ICE_PER_SESSION = 32;
const MAX_EARLY_ICE_SESSIONS = 500;
const EARLY_ICE_TTL_MS = 30_000;

export class P2pManager {
  private readonly sessions = new Map<string, SessionLike>();
  private readonly earlyIce = new Map<string, { at: number; envelopes: SignalEnvelope[] }>();

  constructor(
    private readonly callbacks: P2pManagerCallbacks,
    private readonly sessionFactory: SessionFactory = defaultSessionFactory
  ) {}

  /** Handles one incoming P2pSignal payload (JSON-encoded SignalEnvelope, see relay-session.ts) addressed to sessionId. */
  async handleIncomingSignal(sessionId: string, payloadBytes: Buffer): Promise<void> {
    let envelope: SignalEnvelope;
    try {
      envelope = JSON.parse(payloadBytes.toString('utf-8'));
    } catch {
      logger.warn(`P2P signal for session ${sessionId}: malformed JSON payload — dropping`);
      return;
    }

    let session = this.sessions.get(sessionId);
    if (!session) {
      if (envelope.kind === 'ice') {
        this.bufferEarlyIce(sessionId, envelope);
        return;
      }
      if (envelope.kind !== 'offer') {
        logger.warn(`P2P signal for session ${sessionId}: first message was "${envelope.kind}", expected "offer" — dropping`);
        return;
      }
      session = this.sessionFactory(
        sessionId,
        (outEnvelope) => this.callbacks.sendSignalToServer(sessionId, outEnvelope),
        (bytesRelayedTotal) => this.callbacks.reportTraffic(sessionId, bytesRelayedTotal),
        () => this.sessions.delete(sessionId)
      );
      this.sessions.set(sessionId, session);
      await session.handleSignal(envelope);
      const early = this.earlyIce.get(sessionId);
      this.earlyIce.delete(sessionId);
      for (const ice of early?.envelopes ?? []) {
        await session.handleSignal(ice);
      }
      return;
    }

    await session.handleSignal(envelope);
  }

  private bufferEarlyIce(sessionId: string, envelope: SignalEnvelope): void {
    const now = Date.now();
    for (const [id, entry] of this.earlyIce) {
      if (now - entry.at > EARLY_ICE_TTL_MS) this.earlyIce.delete(id);
    }
    let entry = this.earlyIce.get(sessionId);
    if (!entry) {
      if (this.earlyIce.size >= MAX_EARLY_ICE_SESSIONS) return;
      entry = { at: now, envelopes: [] };
      this.earlyIce.set(sessionId, entry);
    }
    if (entry.envelopes.length < MAX_EARLY_ICE_PER_SESSION) entry.envelopes.push(envelope);
  }

  /** Diagnostic only — the server, not this count, is the real source of truth for relay eligibility/capacity. */
  get activeSessionCount(): number {
    return this.sessions.size;
  }

  shutdown(): void {
    for (const session of this.sessions.values()) session.close();
    this.sessions.clear();
  }
}
