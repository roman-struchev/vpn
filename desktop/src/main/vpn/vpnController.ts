import { EventEmitter } from 'node:events';
import type { ApiClient } from '../api/apiClient';
import type { SystemProxyManager } from '../proxy/systemProxy';
import { XrayProcess } from '../xray/xrayProcess';
import { probeCensorship } from './censorshipProbe';
import { waitForPortOpen } from './portReady';
import type { ConnectionEvent, ConnectionState } from '../../shared/connectionState';
import { ConnectionStateMachine } from '../../shared/connectionState';
import { ReconnectBackoffPolicy, type Fingerprint } from '../../shared/reconnectBackoffPolicy';
import { TransportFallbackPolicy, type Transport } from '../../shared/transportFallbackPolicy';
import { parseVlessUri, type ParsedVlessUri } from '../../shared/vlessUri';
import { buildXrayConfig, HTTP_PORT, type GrpcFallback } from '../../shared/xrayConfigFactory';

export interface VpnControllerEvents {
  state: [ConnectionState];
  region: [string | null];
}

/**
 * Orchestrates one VPN "session": fetches the node/policy list, drives
 * ConnectionStateMachine + ReconnectBackoffPolicy, owns the xray child
 * process and the system-proxy toggle. Mirrors
 * android/.../vpn/XrayVpnService.java one-for-one in responsibility, minus
 * the TUN/foreground-notification specifics that don't apply on desktop.
 */
export class VpnController extends EventEmitter {
  private readonly stateMachine = new ConnectionStateMachine();
  private readonly xrayProcess = new XrayProcess();

  private nodes: ParsedVlessUri[] = [];
  private nodeIndex = 0;
  private backoff: ReconnectBackoffPolicy | null = null;
  private transportFallback: TransportFallbackPolicy | null = null;
  private grpcByHost = new Map<string, GrpcFallback>();
  private nodeIdByHost = new Map<string, number>();
  private stopping = false;
  private retryTimer: NodeJS.Timeout | null = null;

  constructor(
    private readonly apiClient: ApiClient,
    private readonly systemProxy: SystemProxyManager
  ) {
    super();
  }

  getState(): ConnectionState {
    return this.stateMachine.getState();
  }

  async connect(): Promise<void> {
    if (this.getState() === 'CONNECTING' || this.getState() === 'CONNECTED') return;
    this.stopping = false;
    this.transition('CONNECT_REQUESTED');

    try {
      const [policy, links] = await Promise.all([
        this.apiClient.getRoutingConfig(null, null),
        this.apiClient.getSubscriptionLinks(),
      ]);
      if (!links.length) {
        throw new Error('No subscription links available for this account');
      }

      const parsed: ParsedVlessUri[] = [];
      for (const link of links) {
        try {
          parsed.push(parseVlessUri(link));
        } catch (e) {
          console.warn('Skipping unparsable subscription link', e);
        }
      }
      if (!parsed.length) {
        throw new Error('No usable subscription links after parsing');
      }

      this.nodes = parsed;
      this.nodeIndex = 0;
      this.backoff = new ReconnectBackoffPolicy(
        policy.backoffInitialSec,
        policy.maxRetriesBeforeNodeSwitch,
        normalizeFingerprint(policy.fingerprint)
      );

      this.grpcByHost = new Map();
      this.nodeIdByHost = new Map();
      for (const n of policy.nodes) {
        if (n.grpcFallbackPort) {
          this.grpcByHost.set(n.publicIp, { port: n.grpcFallbackPort, serviceName: n.grpcFallbackServiceName ?? undefined });
        }
        this.nodeIdByHost.set(n.publicIp, n.id);
      }
      const initialTransport: Transport = policy.primaryTransport?.toUpperCase() === 'GRPC' ? 'GRPC' : 'XHTTP';
      this.transportFallback = new TransportFallbackPolicy(parsed.length, this.grpcByHost.size > 0, initialTransport);

      await this.attemptStart();
    } catch (e) {
      console.error('Failed to load VPN profile', e);
      this.transition('FATAL_ERROR');
    }
  }

  async disconnect(): Promise<void> {
    this.stopping = true;
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.xrayProcess.stop();
    try {
      await this.systemProxy.disable();
    } catch (e) {
      console.warn('Failed to disable system proxy', e);
    }
    this.transition('DISCONNECT_REQUESTED');
    this.emit('region', null);
  }

  private async attemptStart(): Promise<void> {
    if (this.stopping || !this.backoff || !this.transportFallback) return;
    const vless = this.nodes[this.nodeIndex % this.nodes.length];
    const transport = this.transportFallback.getCurrentTransport();
    const attemptStartedAt = Date.now();

    try {
      const config = buildXrayConfig(vless, this.backoff.getFingerprint(), transport, this.grpcByHost.get(vless.host));
      this.xrayProcess.start(config, (code, signal) => this.onXrayExit(code, signal));

      const ready = await waitForPortOpen(HTTP_PORT);
      if (!ready) {
        this.xrayProcess.stop();
        throw new Error('xray did not start listening in time');
      }

      await this.systemProxy.enable();
      this.backoff.onSuccess();
      this.transition('TUNNEL_UP');
      this.emit('region', vless.remark || vless.host);

      const connectTimeMs = Date.now() - attemptStartedAt;
      const connectedNodeId = this.nodeIdByHost.get(vless.host) ?? null;
      this.reportTelemetry(false, connectedNodeId, connectTimeMs, 0);
    } catch (e) {
      console.warn(`Tunnel start failed on node ${this.nodeIndex} (transport=${transport})`, e);
      await this.handleFailure();
    }
  }

  private onXrayExit(code: number | null, signal: NodeJS.Signals | null): void {
    if (this.stopping) return;
    const state = this.getState();
    if (state === 'CONNECTED' || state === 'CONNECTING' || state === 'RECONNECTING') {
      console.warn(`xray exited unexpectedly (code=${code}, signal=${signal})`);
      void this.handleFailure();
    }
  }

  private async handleFailure(): Promise<void> {
    if (this.stopping || !this.backoff || !this.transportFallback) return;

    // Capture which node this failure is actually about before nodeIndex
    // potentially advances below — telemetry must be attributed to the node
    // that just failed, not to whichever node we're about to try next.
    const failedNodeId = this.nodeIdByHost.get(this.nodes[this.nodeIndex % this.nodes.length].host) ?? null;

    const decision = this.backoff.onFailure();
    let whitelistSuspected = false;

    if (decision.switchNode) {
      this.nodeIndex += 1;
      const transportOutcome = this.transportFallback.onNodeSwitch();

      if (transportOutcome.allTransportsExhausted) {
        const verdict = await probeCensorship();
        whitelistSuspected = verdict === 'OPERATOR_RESTRICTION';
        if (whitelistSuspected) {
          this.reportTelemetry(true, failedNodeId, 0, this.backoff.getConsecutiveFailuresOnNode());
          this.transition('OPERATOR_BLOCK_DETECTED');
          return;
        }
      } else if (transportOutcome.transportChanged) {
        console.log('XHTTP exhausted across all nodes, falling back to gRPC+Reality (Phase 9)');
      }
    }

    this.reportTelemetry(whitelistSuspected, failedNodeId, 0, this.backoff.getConsecutiveFailuresOnNode());
    this.transition('TUNNEL_DOWN');
    this.retryTimer = setTimeout(() => {
      if (!this.stopping) void this.attemptStart();
    }, decision.delaySeconds * 1000);
  }

  /**
   * Best-effort, feeds the admin degradation dashboard and
   * DynamicRoutingService's auto-quarantine (docs/PLAN.md §8), which is keyed
   * off nodeId. Called on both success (connectTimeMs measured, failureCount=0)
   * and failure (connectTimeMs=0, failureCount from the backoff policy) — the
   * dashboard needs the success reports as the denominator for a real failure
   * rate, not just an absolute failure count.
   */
  private reportTelemetry(
    whitelistSuspected: boolean,
    nodeId: number | null,
    connectTimeMs: number,
    failureCount: number
  ): void {
    void this.apiClient.submitTelemetry(
      nodeId,
      null,
      null,
      this.transportFallback?.getCurrentTransport() ?? 'XHTTP',
      connectTimeMs,
      failureCount,
      whitelistSuspected
    );
  }

  private transition(event: ConnectionEvent): void {
    try {
      const next = this.stateMachine.dispatch(event);
      this.emit('state', next);
    } catch (e) {
      console.warn(`Ignored invalid transition: ${event} from ${this.getState()}`, e);
    }
  }
}

function normalizeFingerprint(fingerprint: string): Fingerprint {
  return fingerprint === 'edge' ? 'edge' : 'firefox';
}

export declare interface VpnController {
  on<K extends keyof VpnControllerEvents>(event: K, listener: (...args: VpnControllerEvents[K]) => void): this;
  emit<K extends keyof VpnControllerEvents>(event: K, ...args: VpnControllerEvents[K]): boolean;
}
