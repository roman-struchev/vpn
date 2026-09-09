import os from 'os';
import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';
import { AgentConfig, savePersistedState } from '../config.js';
import { logger } from '../utils/logger.js';
import { XraySupervisor } from '../xray/xray-supervisor.js';
import { StatsCollector } from '../xray/stats-collector.js';

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

  constructor(config: AgentConfig, xraySupervisor: XraySupervisor, statsCollector: StatsCollector) {
    this.config = config;
    this.xraySupervisor = xraySupervisor;
    this.statsCollector = statsCollector;
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
        publicIp: this.config.publicIp,
        agentVersion: '1.0.0',
        region: this.config.region,
        asn: this.config.asn,
        supportedTransports: ['xhttp', 'reality', 'vless'],
      };

      regClient.registerNode(req, (err: any, response: any) => {
        if (err) {
          logger.error('Registration failed:', err);
          return reject(err);
        }

        logger.info(`Registration successful! Assigned Node ID: ${response.nodeId}, Pool: ${response.assignedPool}`);
        this.config.nodeId = parseInt(response.nodeId, 10);
        this.config.nodeToken = String(response.nodeToken);
        if (this.config.nodeId && this.config.nodeToken) {
          savePersistedState(this.config.nodeId, this.config.nodeToken);
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

  private sendHeartbeat(): void {
    if (!this.activeStream || !this.config.nodeId || !this.config.nodeToken) return;

    const totalMem = os.totalmem();
    const freeMem = os.freemem();
    const usedMem = totalMem - freeMem;
    const loadAvg = os.loadavg();
    const cpuCount = os.cpus().length || 1;
    const cpuPercent = Math.min(100, Math.round((loadAvg[0] / cpuCount) * 100 * 10) / 10);
    const xrayStatus = this.xraySupervisor.getStatus();

    const msg = {
      nodeId: this.config.nodeId,
      nodeToken: this.config.nodeToken,
      timestampEpochMs: Date.now(),
      heartbeat: {
        cpuPercent,
        memoryUsedBytes: usedMem,
        memoryTotalBytes: totalMem,
        xrayRunning: xrayStatus.isRunning,
        uptimeSeconds: Math.round(os.uptime()),
        activeConnections: 0,
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

      const success = await this.xraySupervisor.applyConfig(sync);

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
    await this.xraySupervisor.stop();
    logger.info('Agent client shut down successfully');
  }
}
