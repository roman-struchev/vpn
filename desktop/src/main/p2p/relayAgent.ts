import { EventEmitter } from 'node:events';
import net from 'node:net';
import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';
// Named imports, not the default export: node-datachannel's .d.ts declares
// only named exports (PeerConnection, DataChannel, ...), and DescriptionType
// is a `const enum` — imported here as `import type` so esbuild (electron-
// vite's main-process bundler, via externalizeDepsPlugin — node-datachannel
// itself stays a real external package, only THIS file gets transpiled)
// elides it entirely rather than trying to resolve a real runtime binding
// for a TypeScript-only construct; the actual wire values are plain strings
// ('offer'/'answer') cast to the type, never the enum's members.
import { PeerConnection, type DescriptionType } from 'node-datachannel';
import { DestinationBlockedError, resolveAllowedTarget } from './destinationAcl';
import { getAgentProtoPath } from './protoPath';
import { decodeSignal, encodeSignal, type SignalEnvelope } from './signalEnvelope';

export type RelayMode = 'OFF' | 'TIMED' | 'ALWAYS';

const HEARTBEAT_INTERVAL_MS = 30_000;
const TRAFFIC_REPORT_INTERVAL_MS = 30_000;
// No TURN fallback — an accepted design tradeoff (docs §8.1): STUN-only ICE
// works whenever both peers' NATs allow a hole-punched path (the common
// case), and simply fails to connect otherwise, rather than routing real
// relay traffic through a third-party TURN relay that would defeat the
// entire point of avoiding a bandwidth-costly central chokepoint. Verified
// in this same environment (two isolated Docker networks + a public STUN
// server) that the actual DataChannel-negotiation code path is sound; only
// genuine cross-NAT reachability in the wild remains unverified here.
const ICE_SERVERS = ['stun:stun.l.google.com:19302'];

interface RelaySession {
  peer: PeerConnection;
  socket: net.Socket | null;
  bytesRelayed: number;
  lastReportedBytes: number;
  reportTimer: NodeJS.Timeout | null;
}

/**
 * The relay-agent side of P2P relay mode (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8) running in the Electron main process: this
 * app registers itself as a type=p2p Node (reusing the exact same
 * RegisterNode/Heartbeat/SyncStream gRPC flow a VPS agent uses — see
 * agent/src/client/grpc-client.ts, which this class deliberately mirrors),
 * then answers WebRTC offers arriving as p2p_signal messages on that stream
 * and bridges each DataChannel to a destination-ACL-gated TCP socket. It
 * does NOT run a local Xray-core inbound — this peer never speaks or
 * decrypts VLESS/Reality, it's a blind byte pipe between the connecting
 * client (whose own xray-core does understand the protocol) and whatever
 * real egress node the signal names.
 */
export class RelayAgent extends EventEmitter {
  private protoDefs: any;
  private streamClient: any;
  private activeStream: any = null;
  private heartbeatTimer: NodeJS.Timeout | null = null;
  private isShuttingDown = false;

  private nodeId: number | null = null;
  private nodeToken: string | null = null;
  private relayMode: RelayMode = 'OFF';
  private relayExpiresAtEpochMs: number | null = null;

  private readonly sessions = new Map<string, RelaySession>();

  constructor(
    private readonly grpcTarget: string,
    private readonly hostname: string,
    private readonly region: string
  ) {
    super();
  }

  /** Registers as a p2p node (consuming a fresh bootstrap token) and opens the persistent sync stream. */
  async start(bootstrapToken: string, relayMode: RelayMode, relayExpiresAtEpochMs: number | null): Promise<void> {
    this.relayMode = relayMode;
    this.relayExpiresAtEpochMs = relayExpiresAtEpochMs;

    const packageDefinition = protoLoader.loadSync(getAgentProtoPath(), {
      keepCase: false,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
    });
    this.protoDefs = (grpc.loadPackageDefinition(packageDefinition) as any).vpn.agent.v1;

    await this.registerNode(bootstrapToken);
    this.startStreaming();
  }

  /** Changes the relay window; takes effect on the next heartbeat. Server-side eligibility (Node#isEligibleForRelay) is the real enforcement, not this. */
  setRelayMode(mode: RelayMode, relayExpiresAtEpochMs: number | null): void {
    this.relayMode = mode;
    this.relayExpiresAtEpochMs = relayExpiresAtEpochMs;
    this.sendHeartbeat();
  }

  private registerNode(bootstrapToken: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const regClient = new this.protoDefs.AgentRegistrationService(this.grpcTarget, grpc.credentials.createInsecure());
      const req = {
        bootstrapToken,
        hostname: this.hostname,
        // Never dialed for a p2p node — see docs §8.4 and
        // NodeManagementService#registerNode's comment on the server.
        publicIp: '0.0.0.0',
        agentVersion: '1.0.0',
        region: this.region,
        asn: '',
        supportedTransports: [],
        relayMode: this.relayMode,
        relayExpiresAtEpochMs: this.relayExpiresAtEpochMs ?? 0,
      };
      regClient.registerNode(req, (err: any, response: any) => {
        if (err) return reject(err);
        this.nodeId = parseInt(response.nodeId, 10);
        this.nodeToken = String(response.nodeToken);
        resolve();
      });
    });
  }

  private startStreaming(): void {
    if (this.isShuttingDown) return;
    this.streamClient = new this.protoDefs.AgentStreamService(this.grpcTarget, grpc.credentials.createInsecure());
    this.activeStream = this.streamClient.syncStream();

    this.activeStream.on('data', (message: any) => this.handleServerMessage(message));
    this.activeStream.on('error', (err: any) => {
      if (!this.isShuttingDown) {
        this.emit('error', err);
        this.scheduleReconnect();
      }
    });
    this.activeStream.on('end', () => {
      if (!this.isShuttingDown) this.scheduleReconnect();
    });

    this.sendHeartbeat();
    this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), HEARTBEAT_INTERVAL_MS);
  }

  private reconnectTimer: NodeJS.Timeout | null = null;
  private scheduleReconnect(): void {
    if (this.isShuttingDown || this.reconnectTimer) return;
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.startStreaming();
    }, 5000);
  }

  private sendHeartbeat(): void {
    if (!this.activeStream || !this.nodeId || !this.nodeToken) return;
    try {
      this.activeStream.write({
        nodeId: this.nodeId,
        nodeToken: this.nodeToken,
        timestampEpochMs: Date.now(),
        heartbeat: {
          cpuPercent: 0,
          cpuCount: 1,
          memoryUsedBytes: 0,
          memoryTotalBytes: 0,
          xrayRunning: false,
          uptimeSeconds: 0,
          activeConnections: this.sessions.size,
          relayMode: this.relayMode,
          relayExpiresAtEpochMs: this.relayExpiresAtEpochMs ?? 0,
        },
      });
    } catch (err) {
      this.emit('error', err);
    }
  }

  private handleServerMessage(message: any): void {
    if (message.p2pSignal) {
      void this.handleIncomingSignal(message.p2pSignal.sessionId, message.p2pSignal.payload).catch((err) =>
        this.emit('error', err)
      );
    }
    // configSync/command/heartbeatAck: irrelevant to a p2p node's relay role
    // (no local Xray-core to configure — see class doc comment).
  }

  private async handleIncomingSignal(sessionId: string, payload: Buffer): Promise<void> {
    const envelope = decodeSignal(payload);
    let session = this.sessions.get(sessionId);

    if (envelope.kind === 'offer') {
      if (session) return; // duplicate offer for an already-established session — ignore
      session = this.createSession(sessionId);
      this.sessions.set(sessionId, session);

      // Validate + bridge BEFORE answering, so a rejected destination never
      // even gets a DataChannel to talk through.
      let allowedIp: string;
      try {
        allowedIp = await resolveAllowedTarget(envelope.targetHost);
      } catch (e) {
        this.sessions.delete(sessionId);
        session.peer.close();
        if (e instanceof DestinationBlockedError) this.emit('aclRejected', sessionId, envelope.targetHost);
        else this.emit('error', e);
        return;
      }

      session.peer.setRemoteDescription(envelope.sdp, 'offer' as DescriptionType);
      this.wireDataChannelHandlers(sessionId, session, allowedIp, envelope.targetPort);
    } else if (envelope.kind === 'ice') {
      session?.peer.addRemoteCandidate(envelope.candidate, envelope.sdpMid);
    }
    // 'answer' never arrives here — this peer is always the answerer (docs §8.1), never the offerer.
  }

  private createSession(sessionId: string): RelaySession {
    const peer = new PeerConnection(`relay-${sessionId}`, { iceServers: ICE_SERVERS });
    const session: RelaySession = { peer, socket: null, bytesRelayed: 0, lastReportedBytes: 0, reportTimer: null };

    peer.onLocalDescription((sdp, type) => {
      if (type === 'answer') this.sendSignal(sessionId, { kind: 'answer', sdp });
    });
    peer.onLocalCandidate((candidate, mid) => {
      this.sendSignal(sessionId, { kind: 'ice', candidate, sdpMid: mid });
    });
    peer.onStateChange((state) => {
      if (state === 'closed' || state === 'failed' || state === 'disconnected') {
        this.closeSession(sessionId);
      }
    });

    return session;
  }

  private wireDataChannelHandlers(sessionId: string, session: RelaySession, targetIp: string, targetPort: number): void {
    session.peer.onDataChannel((dc) => {
      const socket = net.createConnection({ host: targetIp, port: targetPort });
      session.socket = socket;

      socket.on('connect', () => {
        socket.on('data', (chunk) => {
          try {
            dc.sendMessageBinary(chunk);
          } catch {
            // channel likely closing/closed — the peer's onStateChange cleanup handles teardown.
          }
        });
      });
      socket.on('error', () => this.closeSession(sessionId));
      socket.on('close', () => this.closeSession(sessionId));

      dc.onMessage((msg) => {
        // Always sent as binary by our own side (sendMessageBinary); a
        // string branch only exists because the library's Channel interface
        // allows text messages too — treated as raw UTF-8 bytes, never hit
        // in practice since nothing on either side of this bridge sends text.
        const buf = typeof msg === 'string' ? Buffer.from(msg, 'utf-8') : Buffer.from(msg);
        session.bytesRelayed += buf.length;
        socket.write(buf);
      });
      dc.onClosed(() => this.closeSession(sessionId));

      session.reportTimer = setInterval(() => this.reportTrafficDelta(sessionId, session), TRAFFIC_REPORT_INTERVAL_MS);
    });
  }

  private sendSignal(sessionId: string, envelope: SignalEnvelope): void {
    if (!this.activeStream || !this.nodeId || !this.nodeToken) return;
    try {
      this.activeStream.write({
        nodeId: this.nodeId,
        nodeToken: this.nodeToken,
        timestampEpochMs: Date.now(),
        p2pSignal: { sessionId, payload: encodeSignal(envelope) },
      });
    } catch (err) {
      this.emit('error', err);
    }
  }

  private reportTrafficDelta(sessionId: string, session: RelaySession): void {
    const delta = session.bytesRelayed - session.lastReportedBytes;
    if (delta <= 0) return;
    session.lastReportedBytes = session.bytesRelayed;
    if (!this.activeStream || !this.nodeId || !this.nodeToken) return;
    try {
      this.activeStream.write({
        nodeId: this.nodeId,
        nodeToken: this.nodeToken,
        timestampEpochMs: Date.now(),
        p2pTrafficReport: { sessionId, bytesRelayed: session.bytesRelayed },
      });
    } catch (err) {
      this.emit('error', err);
    }
  }

  private closeSession(sessionId: string): void {
    const session = this.sessions.get(sessionId);
    if (!session) return;
    this.sessions.delete(sessionId);
    if (session.reportTimer) clearInterval(session.reportTimer);
    // Final report so the last partial delta since the last periodic tick isn't lost.
    this.reportTrafficDelta(sessionId, session);
    try {
      session.socket?.destroy();
    } catch {
      // already closed
    }
    try {
      session.peer.close();
    } catch {
      // already closed
    }
  }

  async stop(): Promise<void> {
    this.isShuttingDown = true;
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    for (const sessionId of [...this.sessions.keys()]) this.closeSession(sessionId);
    try {
      this.activeStream?.end();
    } catch {
      // ignore
    }
  }
}
