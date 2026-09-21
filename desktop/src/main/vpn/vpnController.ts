import { EventEmitter } from 'node:events';
import os from 'node:os';
import type { ApiClient } from '../api/apiClient';
import type { SystemProxyManager } from '../proxy/systemProxy';
import { XrayProcess } from '../xray/xrayProcess';
import { probeCensorship } from './censorshipProbe';
import { waitForPortOpen } from './portReady';
import type { ConnectionEvent, ConnectionState } from '../../shared/connectionState';
import { ConnectionStateMachine } from '../../shared/connectionState';
import { ReconnectBackoffPolicy, type Fingerprint } from '../../shared/reconnectBackoffPolicy';
import { TransportFallbackPolicy, type Transport } from '../../shared/transportFallbackPolicy';
import { parseVlessUri, regionLabel, type ParsedVlessUri } from '../../shared/vlessUri';
import {
  buildP2pExitConfig,
  buildXrayConfig,
  HTTP_PORT,
  type GrpcFallback,
  type RussianRoutingMode,
} from '../../shared/xrayConfigFactory';
import { isP2pRegionKey, regionFromKey } from '../../shared/regionKey';
import { reportError } from '../diagnostics';
import { P2pRelayBridge } from '../p2p/relayClient';

/**
 * How many relay peers to try before telling the user the network is blocked.
 * Each attempt is a full WebRTC negotiation, so more would mostly mean a
 * longer wait for the same answer.
 */
const MAX_RELAY_ATTEMPTS = 3;

/**
 * How long to wait before looking for P2P exit peers again once every one of
 * them has failed. Long enough not to hammer the directory, short enough that
 * a peer coming back online is picked up while the user is still waiting.
 */
const P2P_EXIT_RETRY_DELAY_MS = 15_000;

export interface VpnControllerEvents {
  state: [ConnectionState];
  region: [string | null];
  /** true when a pinned region preference had no online node and connect() fell back to all regions. */
  regionFallback: [boolean];
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
  // The P2P hop, when one is in use: a local bridge that carries this
  // connection over somebody else's device — either to one of our nodes (the
  // blocked-network fallback) or all the way out to the internet (a P2P exit
  // the user picked).
  private relayBridge: P2pRelayBridge | null = null;
  // Set for as long as the session is a P2P exit, and the flag the failure
  // path keys off: there is no node list to walk and no transport to fall
  // back through in that mode, only other peers in the same region.
  private p2pExitRegion: string | null = null;
  private grpcByHost = new Map<string, GrpcFallback>();
  private nodeIdByHost = new Map<string, number>();
  private stopping = false;
  private retryTimer: NodeJS.Timeout | null = null;
  // Default preserves the old always-on bypass-RU behavior for anyone who
  // never touches the new control.
  private russianRoutingMode: RussianRoutingMode = 'bypassRu';

  constructor(
    private readonly apiClient: ApiClient,
    private readonly systemProxy: SystemProxyManager
  ) {
    super();
  }

  getState(): ConnectionState {
    return this.stateMachine.getState();
  }

  getRussianRoutingMode(): RussianRoutingMode {
    return this.russianRoutingMode;
  }

  setRussianRoutingMode(mode: RussianRoutingMode): void {
    this.russianRoutingMode = mode;
  }

  /**
   * Re-runs connect() against the current settings if a tunnel is up, so a
   * changed region or RU-routing mode takes effect immediately instead of
   * silently applying only at the next manual connect (both are baked into
   * the running xray's config/node choice, so there's nothing to hot-reload).
   * A no-op while disconnected.
   */
  async reconnectIfActive(): Promise<void> {
    const state = this.getState();
    if (state !== 'CONNECTED' && state !== 'CONNECTING' && state !== 'RECONNECTING') return;
    await this.disconnect();
    await this.connect();
  }

  async checkLiveness(): Promise<void> {
    if (this.getState() !== 'CONNECTED') return;
    const isReady = await waitForPortOpen(HTTP_PORT, '127.0.0.1', 1000);
    if (!isReady) {
      console.warn('Liveness check failed on HTTP proxy port; triggering recovery');
      void this.handleFailure();
    }
  }

  async connect(): Promise<void> {
    if (this.getState() === 'CONNECTING' || this.getState() === 'CONNECTED') return;
    this.stopping = false;
    this.transition('CONNECT_REQUESTED');

    try {
      // Must happen before fetching subscription links, not just after a
      // successful tunnel start (the only other call site, below): a brand
      // new account has zero devices, and the server only ever includes
      // nodes/keys for a user's *existing* devices in the subscription
      // export (SubscriptionExportService#exportVlessLinksForOwnApp
      // deliberately doesn't auto-create one anymore) — so the very first
      // connect() attempt always got "No subscription links available for
      // this account" with no way to recover, since the device that would
      // fix that only ever got registered *after* a tunnel came up.
      await this.registerOrTouchDevice();

      // A P2P exit is its own kind of session, not a region of ours: no
      // subscription links, no node list, no transport fallback — just peers
      // in that region, tried in turn.
      const selection = this.apiClient.getSelectedRegion();
      if (isP2pRegionKey(selection)) {
        const region = regionFromKey(selection as string);
        if (await this.connectThroughP2pExit(region)) {
          this.emit('regionFallback', false);
          return;
        }
        // Nobody in that region could carry it. Falling through to our own
        // servers keeps the user connected, which is what they asked for
        // first — and regionFallback is how the UI says the pick was not
        // honoured, rather than leaving them to wonder why the exit IP is
        // suddenly a datacenter's.
        console.warn(`No P2P peer in "${region}" could carry the connection; falling back to our own nodes`);
      }

      const preferredRegion = await this.resolvePreferredRegion();
      const [policy, linksResp] = await Promise.all([
        this.apiClient.getRoutingConfig(null, null),
        this.apiClient.getSubscriptionLinks(preferredRegion),
      ]);
      const regionFellBack =
        isP2pRegionKey(selection) ||
        (Boolean(preferredRegion) && linksResp.requestedRegionAvailable === false);
      if (regionFellBack) {
        // Sane fallback (per the region-picker spec): the server already
        // substituted the full node list, so connect() proceeds normally —
        // just let the log/UI make clear why the pinned region wasn't honored.
        console.warn(`Preferred region "${preferredRegion}" has no online node right now; falling back to all regions`);
      }
      this.emit('regionFallback', regionFellBack);
      const links = linksResp.links;
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
      // The user-visible dead end ("connected, nothing works" starts here) —
      // worth reporting with the reason, since the user only sees the state.
      reportError('vpn', 'PROFILE_LOAD_FAILED', 'Failed to load the VPN profile', e);
      console.error('Failed to load VPN profile', e);
      this.transition('FATAL_ERROR');
    }
  }

  /**
   * Normally just the user's manually-pinned region (or null = auto). In
   * 'onlyRu' mode with no manual pin, prefer a Russia-located node instead —
   * required for RU-geo-restricted sites to actually work through the tunnel;
   * a non-Russian exit is useless for that regardless of the routing rules.
   * Falls back to the manual/no preference if no Russian node is currently
   * online — surfaced to the user via the region list's own accessibility
   * info in the renderer, not silently pretended to work here.
   */
  private async resolvePreferredRegion(): Promise<string | null> {
    // A P2P pick never reaches here (connect() handles it and returns), so
    // whatever is stored at this point is a plain region of ours — except
    // after a P2P attempt that found nobody, where the fallback is
    // deliberately "any region" rather than that region's servers.
    const stored = this.apiClient.getSelectedRegion();
    const manualRegion = isP2pRegionKey(stored) ? null : stored;
    if (this.russianRoutingMode !== 'onlyRu' || manualRegion) {
      return manualRegion;
    }
    try {
      const regions = await this.apiClient.getRegions();
      const ruRegion = regions.find((r) => r.accessible && !r.p2p && /russia/i.test(r.region));
      if (ruRegion) return ruRegion.region;
      console.warn('RU-only routing mode is active but no Russian-region node is currently online; connecting without a region preference.');
    } catch (e) {
      console.warn('Failed to look up a Russian-region node for RU-only routing mode', e);
    }
    return manualRegion;
  }

  async disconnect(): Promise<void> {
    this.stopping = true;
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.xrayProcess.stop();
    this.p2pExitRegion = null;
    await this.teardownRelayBridge();
    try {
      await this.systemProxy.disable();
    } catch (e) {
      // Leaving the system proxy pointing at a stopped tunnel is what makes
      // the whole machine appear offline after disconnecting.
      reportError('proxy', 'PROXY_DISABLE_FAILED', 'Failed to disable the system proxy on disconnect', e);
      console.warn('Failed to disable system proxy', e);
    }
    if (this.getState() !== 'DISCONNECTED') {
      this.transition('DISCONNECT_REQUESTED');
    }
    this.emit('region', null);
    this.emit('regionFallback', false);
  }

  private async attemptStart(): Promise<void> {
    if (this.stopping || !this.backoff || !this.transportFallback) return;
    const vless = this.nodes[this.nodeIndex % this.nodes.length];
    const transport = this.transportFallback.getCurrentTransport();
    const attemptStartedAt = Date.now();

    try {
      const config = buildXrayConfig(
        vless,
        this.backoff.getFingerprint(),
        transport,
        this.grpcByHost.get(vless.host),
        { russianRoutingMode: this.russianRoutingMode }
      );
      this.xrayProcess.start(config, (code, signal) => this.onXrayExit(code, signal));

      const ready = await waitForPortOpen(HTTP_PORT);
      if (!ready) {
        this.xrayProcess.stop();
        throw new Error('xray did not start listening in time');
      }

      await this.systemProxy.enable();
      this.backoff.onSuccess();
      this.transition('TUNNEL_UP');
      this.emit('region', vless.remark ? regionLabel(vless.remark) : vless.host);

      const connectTimeMs = Date.now() - attemptStartedAt;
      const connectedNodeId = this.nodeIdByHost.get(vless.host) ?? null;
      this.reportTelemetry(false, connectedNodeId, connectTimeMs, 0);
      void this.registerOrTouchDevice();
    } catch (e) {
      console.warn(`Tunnel start failed on node ${this.nodeIndex} (transport=${transport})`, e);
      await this.handleFailure();
    }
  }

  /**
   * Connects with another user's device as the *exit* — the session the user
   * asked for by picking a P2P row in the region list (docs/research/
   * P2P_RELAY_FEASIBILITY.md §8.9).
   *
   * Nothing of ours is in the path: xray's outbound is a SOCKS5 hop into the
   * local bridge, each connection becomes its own WebRTC session, and the
   * peer opens the TCP connection to the site itself. So the exit IP is that
   * person's, which is the entire point — and also why the peers are tried in
   * turn rather than a single one being trusted: they are phones and laptops,
   * and one going offline mid-attempt is ordinary.
   */
  private async connectThroughP2pExit(region: string): Promise<boolean> {
    let exits: { nodeId: number; region: string | null }[];
    try {
      exits = await this.apiClient.getP2pExits(region);
    } catch (e) {
      reportError('p2p-exit', 'EXIT_LOOKUP_FAILED', 'Could not look up P2P exit peers', e);
      console.warn('Could not look up P2P exit peers', e);
      return false;
    }
    if (!exits.length) {
      // Also what a trial account gets: the row is shown locked, and asking
      // anyway is answered with an empty list rather than an error.
      console.log(`No P2P exit peer is available in "${region}" right now`);
      return false;
    }

    for (const exit of exits.slice(0, MAX_RELAY_ATTEMPTS)) {
      if (this.stopping) return false;
      console.log(`Connecting out through peer ${exit.nodeId} in ${exit.region ?? region}`);
      await this.teardownRelayBridge();

      const bridge = new P2pRelayBridge(this.apiClient, exit.nodeId, 'socks');
      try {
        const localPort = await bridge.start();
        this.relayBridge = bridge;

        this.xrayProcess.start(
          buildP2pExitConfig({ host: '127.0.0.1', port: localPort }, { russianRoutingMode: this.russianRoutingMode }),
          (code, signal) => this.onXrayExit(code, signal)
        );

        const ready = await waitForPortOpen(HTTP_PORT);
        if (!ready) {
          this.xrayProcess.stop();
          throw new Error('xray did not start listening in time on the P2P exit');
        }
        await this.systemProxy.enable();

        this.p2pExitRegion = region;
        this.transition('TUNNEL_UP');
        // Labelled as what it is: the exit is a person's own connection, so
        // the speed is their uplink and the IP is residential. Presenting it
        // as an ordinary region would set the wrong expectation.
        this.emit('region', `${region} (P2P)`);
        void this.registerOrTouchDevice();
        return true;
      } catch (e) {
        console.warn(`P2P exit peer ${exit.nodeId} did not work out`, e);
        reportError('p2p-exit', 'EXIT_CONNECT_FAILED', 'Could not connect out through a P2P exit peer', e, {
          exitNodeId: String(exit.nodeId),
        });
        await this.teardownRelayBridge();
      }
    }
    return false;
  }

  /**
   * A P2P exit session that dropped. There is no node list to advance and no
   * transport to fall back through here — the only thing that can change the
   * outcome is a different peer, so this tears the session down and starts
   * looking for one.
   */
  private async handleP2pExitFailure(): Promise<void> {
    const region = this.p2pExitRegion;
    if (this.stopping || !region) return;

    this.xrayProcess.stop();
    await this.teardownRelayBridge();
    this.transition('TUNNEL_DOWN');
    await this.retryP2pExit(region);
  }

  /**
   * Works down the peers in that region, and keeps coming back to it on a
   * timer for as long as none of them works — rather than silently moving
   * the user onto our own servers mid-session. They chose this exit, and a
   * reconnect is not the moment to quietly change what their traffic looks
   * like from the outside; disconnecting is how they change their mind.
   */
  private async retryP2pExit(region: string): Promise<void> {
    // Cleared while an attempt is in flight so a failure inside it cannot
    // re-enter handleP2pExitFailure; set back below if the attempt fails, so
    // this session still counts as a P2P exit for whatever fails next.
    this.p2pExitRegion = null;
    if (this.stopping) return;
    if (await this.connectThroughP2pExit(region)) return;

    this.p2pExitRegion = region;
    this.retryTimer = setTimeout(() => {
      if (!this.stopping) void this.retryP2pExit(region);
    }, P2P_EXIT_RETRY_DELAY_MS);
  }

  /**
   * Last resort before declaring the network blocked: reach a node *through*
   * another user's device.
   *
   * The relay dials the node for us and pipes opaque bytes; the VLESS/Reality
   * session still terminates at the node itself, so nothing about the tunnel's
   * secrecy changes — only how the packets get there. That is why the outbound
   * keeps the node's own SNI, UUID and transport and merely dials the local
   * bridge instead (see XrayConfigOptions#dialThrough).
   *
   * Tries a handful of relays rather than all of them: each attempt costs a
   * negotiation, and if the first few peers cannot be reached, the honest
   * answer to the user is sooner rather than later.
   */
  private async tryRelayedConnection(): Promise<boolean> {
    if (this.stopping || !this.backoff) return false;

    let relays: { nodeId: number; region: string | null }[];
    try {
      relays = await this.apiClient.getP2pRelays();
    } catch (e) {
      console.warn('Could not look up P2P relays', e);
      return false;
    }
    if (relays.length === 0) {
      console.log('No P2P relay peers are available right now');
      return false;
    }

    const vless = this.nodes[this.nodeIndex % this.nodes.length];
    for (const relay of relays.slice(0, MAX_RELAY_ATTEMPTS)) {
      if (this.stopping) return false;
      console.log(`Trying to reach ${vless.host} through relay node ${relay.nodeId} (${relay.region ?? 'unknown region'})`);
      await this.teardownRelayBridge();

      const bridge = new P2pRelayBridge(this.apiClient, relay.nodeId, { host: vless.host, port: vless.port });
      try {
        const localPort = await bridge.start();
        this.relayBridge = bridge;

        const config = buildXrayConfig(
          vless,
          this.backoff.getFingerprint(),
          this.transportFallback?.getCurrentTransport() ?? 'XHTTP',
          this.grpcByHost.get(vless.host),
          { russianRoutingMode: this.russianRoutingMode, dialThrough: { host: '127.0.0.1', port: localPort } }
        );
        this.xrayProcess.start(config, (code, signal) => this.onXrayExit(code, signal));

        const ready = await waitForPortOpen(HTTP_PORT);
        if (!ready) {
          this.xrayProcess.stop();
          throw new Error('xray did not start listening in time over the relay');
        }
        await this.systemProxy.enable();
        this.backoff.onSuccess();
        this.transition('TUNNEL_UP');
        // Say so in the status: a relayed connection goes through a stranger's
        // device and is usually slower, so presenting it as an ordinary
        // connection would be misleading.
        this.emit('region', `${vless.remark ? regionLabel(vless.remark) : vless.host} (via peer)`);
        void this.registerOrTouchDevice();
        return true;
      } catch (e) {
        console.warn(`Relay node ${relay.nodeId} did not work out`, e);
        reportError('p2p-relay', 'RELAY_CONNECT_FAILED', 'Could not reach a node through a relay peer', e, {
          relayNodeId: String(relay.nodeId),
        });
        await this.teardownRelayBridge();
      }
    }
    return false;
  }

  /** Closes the P2P hop, if one is up. Safe to call when there is none. */
  private async teardownRelayBridge(): Promise<void> {
    const bridge = this.relayBridge;
    this.relayBridge = null;
    if (!bridge) return;
    try {
      await bridge.stop();
    } catch {
      // already gone
    }
  }

  /** Whether this connection currently runs through another user's device, as a path or as the exit. */
  isRelayed(): boolean {
    return this.relayBridge !== null;
  }

  /** Whether another user's device is the exit for this connection (not merely a hop to one of our nodes). */
  isP2pExit(): boolean {
    return this.p2pExitRegion !== null;
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
    if (this.stopping) return;
    // A P2P exit session has neither of the two things this method works
    // with (a node list and a transport ladder) — and would read
    // this.nodes[0] of an empty array on its way to finding that out.
    if (this.p2pExitRegion) {
      await this.handleP2pExitFailure();
      return;
    }
    if (!this.backoff || !this.transportFallback) return;

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
          // Every direct path to every node has failed, and the probe says the
          // network itself is doing it. This is precisely the situation P2P
          // relaying exists for: another user's device can still reach a node
          // we cannot, and will forward our bytes to it. Only if no relay is
          // available (or none works) do we fall through to telling the user
          // their operator is blocking us.
          if (await this.tryRelayedConnection()) {
            return;
          }
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

  /**
   * Best-effort (errors are swallowed — a transient failure here shouldn't
   * itself abort connect(); if it leaves the account with zero devices, the
   * subsequent "No subscription links available" check already reports
   * that). Keeps this install counting as a "recently active" device
   * (server-side DEVICE_ACTIVE_WINDOW_DAYS) with no manual "add device"
   * step. Touches the locally-persisted device from a prior run first; only
   * registers a new one if that 404s (never registered yet, or revoked
   * elsewhere) — see TokenStore.getDeviceId.
   *
   * Called both before fetching subscription links (so a brand-new account
   * has a device to be paired with a node at all) and again after a
   * successful tunnel start (to keep an existing device's last-active
   * timestamp fresh on every connect, not just the first one).
   */
  private async registerOrTouchDevice(): Promise<void> {
    try {
      const deviceId = this.apiClient.getDeviceId();
      if (deviceId && (await this.apiClient.touchDevice(deviceId))) {
        return;
      }
      const platform = process.platform === 'darwin' ? 'MACOS' : process.platform === 'win32' ? 'WINDOWS' : 'THIRD_PARTY';
      const device = await this.apiClient.addDevice(os.hostname(), platform);
      this.apiClient.saveDeviceId(device.deviceId);
    } catch (e) {
      console.warn('Failed to register/touch this device (best-effort)', e);
    }
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
