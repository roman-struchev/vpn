import { EventEmitter } from 'node:events';
import type { ApiClient } from '../api/apiClient';
import type { SystemProxyManager } from '../proxy/systemProxy';
import { XrayProcess } from '../xray/xrayProcess';
import { probeCensorship } from './censorshipProbe';
import { waitForPortOpen } from './portReady';
import type { ConnectionEvent, ConnectionState } from '../../shared/connectionState';
import { ConnectionStateMachine } from '../../shared/connectionState';
import { ReconnectBackoffPolicy, type Fingerprint } from '../../shared/reconnectBackoffPolicy';
import { parseVlessUri, type ParsedVlessUri } from '../../shared/vlessUri';
import { buildXrayConfig, HTTP_PORT } from '../../shared/xrayConfigFactory';

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
    if (this.stopping || !this.backoff) return;
    const vless = this.nodes[this.nodeIndex % this.nodes.length];

    try {
      const config = buildXrayConfig(vless, this.backoff.getFingerprint());
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
    } catch (e) {
      console.warn(`Tunnel start failed on node ${this.nodeIndex}`, e);
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
    if (this.stopping || !this.backoff) return;

    const decision = this.backoff.onFailure();
    let whitelistSuspected = false;

    if (decision.switchNode) {
      this.nodeIndex += 1;
      const verdict = await probeCensorship();
      whitelistSuspected = verdict === 'OPERATOR_RESTRICTION';
      if (whitelistSuspected) {
        this.reportTelemetry(whitelistSuspected);
        this.transition('OPERATOR_BLOCK_DETECTED');
        return;
      }
    }

    this.reportTelemetry(whitelistSuspected);
    this.transition('TUNNEL_DOWN');
    this.retryTimer = setTimeout(() => {
      if (!this.stopping) void this.attemptStart();
    }, decision.delaySeconds * 1000);
  }

  private reportTelemetry(whitelistSuspected: boolean): void {
    // Best-effort, feeds the admin degradation dashboard (docs/PLAN.md §8).
    void this.apiClient.submitTelemetry(
      null,
      null,
      null,
      'XHTTP',
      0,
      this.backoff?.getConsecutiveFailuresOnNode() ?? 0,
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
