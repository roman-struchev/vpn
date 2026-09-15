import os from 'os';
import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';
import { AgentConfig, savePersistedState } from '../config.js';
import { logger } from '../utils/logger.js';
import { XraySupervisor } from '../xray/xray-supervisor.js';
import { StatsCollector } from '../xray/stats-collector.js';
import { P2pManager } from '../p2p/p2p-manager.js';
import { SignalEnvelope } from '../p2p/relay-session.js';

export class AgentGrpcClient {
  private config: AgentConfig;
  private xraySupervisor: XraySupervisor;
  private statsCollector: StatsCollector;
  private protoDefs: any;
  private streamClient: any;
  private activeStream: any = null;
  private heartbeatTimer: NodeJS.Timeout | null = null;
  private statsTimer: NodeJS.Timeout | null = null;
  private isShuttingDown: boolean = false;
  private currentConfigVersion: number = 0;
  private currentConfigHash: string = '';
  // Distinct users with traffic in the last sendTrafficStats() poll — read by
  // the next sendHeartbeat() as a "recently active" proxy (see Heartbeat.
  // active_connections in agent.proto for why this isn't a literal live count).
  private lastActiveUserCount: number = 0;
  // Previous os.cpus() snapshot (summed across all cores), used to derive an
  // actual CPU-busy percentage over the last heartbeat interval by diffing
  // cumulative tick counters — see sendHeartbeat() for why this replaced
  // os.loadavg()[0], which is a decaying ~1-minute average, not an
  // instantaneous "percent busy right now" figure.
  private lastCpuSnapshot: { idle: number; total: number } | null = null;
  // Bridges every active p2p relay session this node is currently handling
  // (docs/research/P2P_RELAY_FEASIBILITY.md §8) — only ever populated once
  // this.nodeType resolves to p2p (see resolveNodeTypeAfterRegistration()
  // and handleServerMessage()'s p2pSignal branch below). A direct/cdn node
  // never touches this.
  private readonly p2pManager: P2pManager;
  // Known after registerNode()'s response (assignedType) or from
  // config.nodeType when NODE_ID/NODE_TOKEN skip registration entirely.
  // Gates whether ConfigSync is ever handed to XraySupervisor at all — a p2p
  // node never runs Xray-core (see relay-session.ts's header comment).
  private nodeType: string | undefined;

  constructor(config: AgentConfig, xraySupervisor: XraySupervisor, statsCollector: StatsCollector) {
    this.config = config;
    this.xraySupervisor = xraySupervisor;
    this.statsCollector = statsCollector;
    this.nodeType = config.nodeType;
    this.p2pManager = new P2pManager({
      sendSignalToServer: (sessionId, envelope) => this.sendP2pSignal(sessionId, envelope),
      reportTraffic: (sessionId, bytesRelayedTotal) => this.sendP2pTrafficReport(sessionId, bytesRelayedTotal),
    });
  }

  private isP2pNode(): boolean {
    return (this.nodeType || '').toUpperCase() === 'NODE_TYPE_P2P' || (this.nodeType || '').toLowerCase() === 'p2p';
  }

  public async init(): Promise<void> {
    const packageDefinition = protoLoader.loadSync(this.config.protoPath, {
      keepCase: false,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
    });
    this.protoDefs = (grpc.loadPackageDefinition(packageDefinition) as any).vpn.agent.v1;

    // Check if we need to register first
    if (!this.config.nodeId || !this.config.nodeToken) {
      if (!this.config.bootstrapToken) {
        throw new Error('Neither node credentials (NODE_ID + NODE_TOKEN) nor BOOTSTRAP_TOKEN was provided');
      }
      await this.registerNode();
    }

    this.startStreaming();
  }

  private registerNode(): Promise<void> {
    return new Promise((resolve, reject) => {
      logger.info(`Attempting node registration with bootstrap token to ${this.config.serverGrpcUrl}...`);
      const regClient = new this.protoDefs.AgentRegistrationService(
        this.config.serverGrpcUrl,
        grpc.credentials.createInsecure()
      );

      const req = {
        bootstrapToken: this.config.bootstrapToken,
        hostname: this.config.hostname,
        // Meaningless for a p2p node (docs §8.4) — the server never dials it
        // out for a direct-dial VLESS link, so any placeholder is fine; this
        // agent still sends whatever PUBLIC_IP/default was configured rather
        // than special-casing it, since the value truly doesn't matter here.
        publicIp: this.config.publicIp,
        agentVersion: '1.0.0',
        region: this.config.region,
        asn: this.config.asn,
        supportedTransports: ['xhttp', 'reality', 'vless'],
        relayMode: this.config.relayMode,
        relayExpiresAtEpochMs: this.config.relayExpiresAtEpochMs || 0,
      };

      regClient.registerNode(req, (err: any, response: any) => {
        if (err) {
          logger.error('Registration failed:', err);
          return reject(err);
        }

        logger.info(
          `Registration successful! Assigned Node ID: ${response.nodeId}, Pool: ${response.assignedPool}, Type: ${response.assignedType}`
        );
        this.config.nodeId = parseInt(response.nodeId, 10);
        this.config.nodeToken = String(response.nodeToken);
        this.nodeType = response.assignedType ? String(response.assignedType) : this.nodeType;
        if (this.isP2pNode()) {
          logger.info('This node is registered as a p2p relay node — Xray-core will not be started (see docs §8).');
        }
        if (this.config.nodeId && this.config.nodeToken) {
          savePersistedState(this.config.nodeId, this.config.nodeToken, this.nodeType);
        }
        resolve();
      });
    });
  }

  private reconnectAttempt: number = 0;
  private reconnectTimer: NodeJS.Timeout | null = null;

  private startStreaming(): void {
    if (this.isShuttingDown) return;

    logger.info(`Opening gRPC sync stream to ${this.config.serverGrpcUrl} for node ${this.config.nodeId}...`);
    this.streamClient = new this.protoDefs.AgentStreamService(
      this.config.serverGrpcUrl,
      grpc.credentials.createInsecure()
    );

    this.activeStream = this.streamClient.syncStream();

    this.activeStream.on('data', (message: any) => {
      this.reconnectAttempt = 0; // successfully receiving data, reset backoff
      this.handleServerMessage(message);
    });

    this.activeStream.on('error', (err: any) => {
      if (!this.isShuttingDown) {
        logger.warn(`Sync stream error: ${err.message}.`);
        this.scheduleReconnect();
      }
    });

    this.activeStream.on('end', () => {
      if (!this.isShuttingDown) {
        logger.warn('Sync stream closed by server.');
        this.scheduleReconnect();
      }
    });

    // Start periodic heartbeat
    this.sendHeartbeat();
    this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), this.config.heartbeatIntervalMs);

    // Start periodic traffic stats reporting
    this.statsTimer = setInterval(() => this.sendTrafficStats(), this.config.statsIntervalMs);
  }

  private scheduleReconnect(): void {
    this.cleanupStream();
    if (this.isShuttingDown || this.reconnectTimer) return;

    // Exponential backoff with jitter: min 1s, factor 2, max 60s
    const baseDelay = Math.min(60000, 1000 * Math.pow(2, this.reconnectAttempt));
    const jitter = Math.floor(Math.random() * 1000);
    const delay = baseDelay + jitter;
    this.reconnectAttempt++;

    logger.info(`Reconnecting gRPC stream in ${delay}ms (attempt ${this.reconnectAttempt})...`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.startStreaming();
    }, delay);
  }

  // Snapshots and sums os.cpus()' cumulative per-core tick counters (ms since
  // boot). Diffing two snapshots taken heartbeatIntervalMs apart gives the
  // fraction of that interval the CPU was actually busy, which — unlike
  // os.loadavg()[0] (an exponentially-decaying ~1-minute average that keeps
  // reporting elevated load for a while after a burst of activity has
  // already ended) — reflects only what happened since the last heartbeat.
  private static snapshotCpuTimes(): { idle: number; total: number } {
    let idle = 0;
    let total = 0;
    for (const cpu of os.cpus()) {
      idle += cpu.times.idle;
      for (const t of Object.values(cpu.times)) {
        total += t;
      }
    }
    return { idle, total };
  }

  private computeCpuPercent(): number {
    const snapshot = AgentGrpcClient.snapshotCpuTimes();
    const previous = this.lastCpuSnapshot;
    this.lastCpuSnapshot = snapshot;

    // First heartbeat has nothing to diff against yet — report 0 rather than
    // a misleading reading (and definitely not a crash from dividing by the
    // still-unknown interval delta).
    if (!previous) return 0;

    const idleDelta = snapshot.idle - previous.idle;
    const totalDelta = snapshot.total - previous.total;
    if (totalDelta <= 0) return 0;

    const busyPercent = 100 * (1 - idleDelta / totalDelta);
    return Math.min(100, Math.max(0, Math.round(busyPercent * 10) / 10));
  }

  private sendHeartbeat(): void {
    if (!this.activeStream || !this.config.nodeId || !this.config.nodeToken) return;

    const totalMem = os.totalmem();
    const freeMem = os.freemem();
    const usedMem = totalMem - freeMem;
    const cpuCount = os.cpus().length || 1;
    const cpuPercent = this.computeCpuPercent();
    const xrayStatus = this.xraySupervisor.getStatus();

    const msg = {
      nodeId: this.config.nodeId,
      nodeToken: this.config.nodeToken,
      timestampEpochMs: Date.now(),
      heartbeat: {
        cpuPercent,
        cpuCount,
        memoryUsedBytes: usedMem,
        memoryTotalBytes: totalMem,
        xrayRunning: xrayStatus.isRunning,
        uptimeSeconds: Math.round(os.uptime()),
        activeConnections: this.lastActiveUserCount,
        // Re-sent on every heartbeat so a running relay agent can change/
        // extend/cancel its own relay window without re-registering (docs
        // §8.5) — meaningless for a direct/cdn node, which never sets these
        // away from the OFF default and whose heartbeat the server simply
        // ignores for eligibility purposes either way.
        relayMode: this.config.relayMode,
        relayExpiresAtEpochMs: this.config.relayExpiresAtEpochMs || 0,
      },
    };

    try {
      this.activeStream.write(msg);
      logger.debug(`Sent heartbeat: CPU ${cpuPercent}%, RAM ${Math.round((usedMem / totalMem) * 100)}%`);
    } catch (err) {
      logger.warn('Failed to send heartbeat message:', err);
    }
  }

  private async sendTrafficStats(): Promise<void> {
    if (!this.activeStream || !this.config.nodeId || !this.config.nodeToken) return;

    try {
      const deltas = await this.statsCollector.collectDeltas();
      this.lastActiveUserCount = deltas.length;
      if (deltas.length === 0) return;

      const msg = {
        nodeId: this.config.nodeId,
        nodeToken: this.config.nodeToken,
        timestampEpochMs: Date.now(),
        trafficStats: {
          deltas: deltas.map(d => ({
            userId: d.userId,
            deviceId: d.deviceId,
            uuid: d.uuid,
            uplinkBytes: d.uplinkBytes,
            downlinkBytes: d.downlinkBytes,
          })),
        },
      };

      this.activeStream.write(msg);
      logger.info(`Reported traffic stats for ${deltas.length} users`);
    } catch (err) {
      logger.warn('Failed to send traffic stats:', err);
    }
  }

  private async handleServerMessage(message: any): Promise<void> {
    if (message.heartbeatAck) {
      logger.debug('Received HeartbeatAck from server');
    } else if (message.configSync) {
      const sync = message.configSync;
      logger.info(`Received ConfigSync version ${sync.configVersion} with ${sync.clients?.length || 0} clients`);

      // A p2p node never runs Xray-core (docs §8: it's a protocol-blind
      // WebRTC<->TCP byte pipe, see relay-session.ts's header comment) — its
      // ConfigSync carries no meaningful inbound (Reality disabled, no TLS
      // cert configured either, per NodeManagementService#buildNodeConfigSync's
      // isP2p branch on the server side), so applying it would be pointless
      // at best. Ack success unconditionally instead: there is genuinely
      // nothing to fail here.
      const success = this.isP2pNode() ? true : await this.xraySupervisor.applyConfig(sync);

      this.currentConfigVersion = parseInt(sync.configVersion, 10);
      this.currentConfigHash = sync.configHash;

      // Send ConfigAck
      const ackMsg = {
        nodeId: this.config.nodeId,
        nodeToken: this.config.nodeToken,
        timestampEpochMs: Date.now(),
        configAck: {
          configVersion: this.currentConfigVersion,
          configHash: this.currentConfigHash,
          status: success ? 'ACK_STATUS_SUCCESS' : 'ACK_STATUS_ERROR',
          errorMessage: success ? '' : 'Failed to apply configuration to Xray',
        },
      };

      this.activeStream.write(ackMsg);
      logger.info(`Sent ConfigAck (status: ${success ? 'SUCCESS' : 'ERROR'}) for version ${sync.configVersion}`);
    } else if (message.command) {
      logger.info(`Received server command: ${message.command.commandType}`);
      if (message.command.commandType === 'COMMAND_TYPE_RESTART_XRAY') {
        await this.xraySupervisor.restart();
      }
      // COMMAND_TYPE_DRAIN is declared in agent.proto but intentionally has no
      // handler here yet — it's a future "stop accepting new xray connections,
      // let in-flight ones finish, then it's safe to restart/take the node
      // down" primitive, not implemented today. Wiring it to a no-op admin
      // button would be worse than not having the button: an operator could
      // believe they'd achieved a graceful drain when nothing happened. The
      // practical need this would serve is already covered well enough for
      // now by the DRAINING node status (see AdminController#updateNodeStatus
      // and NodeRepository#findByStatus("ONLINE") in DynamicRoutingService /
      // SubscriptionExportService): flipping a node to DRAINING stops new
      // clients from being routed to it while existing sessions are left
      // alone, which is the outcome that matters for planned maintenance.
    } else if (message.p2pSignal) {
      // A connecting client's SDP offer / ICE candidate, forwarded to this
      // node by AgentStreamServiceImpl (docs §8.1) — opaque to both this
      // agent and the server beyond the JSON envelope P2pManager/RelaySession
      // parse themselves (see relay-session.ts's SignalEnvelope). Routed
      // purely by session_id; the server never inspects it either.
      const sessionId = String(message.p2pSignal.sessionId);
      const payload = Buffer.isBuffer(message.p2pSignal.payload)
        ? message.p2pSignal.payload
        : Buffer.from(message.p2pSignal.payload || '', 'base64');
      await this.p2pManager.handleIncomingSignal(sessionId, payload);
    }
  }

  private sendP2pSignal(sessionId: string, envelope: SignalEnvelope): void {
    if (!this.activeStream || !this.config.nodeId || !this.config.nodeToken) return;
    const msg = {
      nodeId: this.config.nodeId,
      nodeToken: this.config.nodeToken,
      timestampEpochMs: Date.now(),
      p2pSignal: {
        sessionId,
        payload: Buffer.from(JSON.stringify(envelope), 'utf-8'),
      },
    };
    try {
      this.activeStream.write(msg);
    } catch (err) {
      logger.warn(`Failed to send p2p signal for session ${sessionId}:`, err);
    }
  }

  private sendP2pTrafficReport(sessionId: string, bytesRelayedTotal: number): void {
    if (!this.activeStream || !this.config.nodeId || !this.config.nodeToken) return;
    const msg = {
      nodeId: this.config.nodeId,
      nodeToken: this.config.nodeToken,
      timestampEpochMs: Date.now(),
      p2pTrafficReport: {
        sessionId,
        bytesRelayed: bytesRelayedTotal,
      },
    };
    try {
      this.activeStream.write(msg);
      logger.debug(`Reported p2p traffic for session ${sessionId}: ${bytesRelayedTotal} bytes relayed so far`);
    } catch (err) {
      logger.warn(`Failed to send p2p traffic report for session ${sessionId}:`, err);
    }
  }

  private cleanupStream(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    if (this.statsTimer) {
      clearInterval(this.statsTimer);
      this.statsTimer = null;
    }
    if (this.reconnectTimer && this.isShuttingDown) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.activeStream) {
      try {
        this.activeStream.end();
      } catch (e) {
        // ignore
      }
      this.activeStream = null;
    }
  }

  public async shutdown(): Promise<void> {
    this.isShuttingDown = true;
    this.cleanupStream();
    this.p2pManager.shutdown();
    await this.xraySupervisor.stop();
    logger.info('Agent client shut down successfully');
  }
}
