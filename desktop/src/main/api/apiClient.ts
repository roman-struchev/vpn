import { app } from 'electron';
import { ApiHostRotation } from '../../shared/apiHostRotation';
import { firstLinkForRegion } from '../../shared/vlessUri';
import { isP2pRegionKey } from '../../shared/regionKey';
import { pingTcp } from '../vpn/pingUtil';
import { shouldRenew } from '../../shared/session';
import { parseVlessUri, regionLabel } from '../../shared/vlessUri';
import type { TokenStore } from './tokenStore';

// Temporarily pointed at the test server (217.216.79.46:8080) instead of the
// vpn.struchev.site production domain — switch back once that's live again.
const DEFAULT_BASE_URL = 'http://217.216.79.46:8080/';
const DEV_DEFAULT_BASE_URL = 'http://localhost:8080/';

export interface AuthResponse {
  token: string;
  userId: number;
  email: string;
  role: string;
  referralCode: string;
}

export interface UserProfile {
  id: number;
  email: string;
  role: string;
  balanceUsdtMicro: number;
  referralCode: string;
  referralCount?: number;
  referralEarningsUsdtMicro?: number;
  /** Ready-to-share plain web link built by the server (`<site>/?ref=CODE`). */
  referralLink?: string;
  referralTelegramLink?: string;
  /**
   * No password/Telegram/Google credential — a no-signup device-trial
   * account (see deviceLogin), not one the user consciously created. The
   * renderer uses this to hide account-management UI that doesn't make
   * sense for it yet (devices, logout) in favor of a sign-in/register CTA.
   */
  isGuest: boolean;
  hasActiveSubscription: boolean;
  subscription?: {
    id: number;
    tariffId: string;
    trafficUsedBytes: number;
    trafficLimitBytes: number;
    expiresAt: string;
    /** No real end date (the server still sends a far-future expiresAt) — show "never", not a date in 2126. */
    noExpiry?: boolean;
  };
}

export interface DeviceDto {
  id: number;
  deviceName: string;
  platform: string;
  isActive: boolean;
  createdAt: string;
  lastSeenAt?: string;
}

export interface RegionInfo {
  region: string;
  nodeCount: number;
  avgCpuPercent: number | null;
  avgActiveConnections: number;
  loadLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  // Whether the caller's own subscription can actually connect through this
  // region right now (see SubscriptionExportService.RegionSummary#accessible
  // on the server) — false does NOT mean hidden: paid regions are still
  // listed to a trial user so they can see what a higher plan unlocks, the
  // client just has to grey those out / block picking them instead of
  // reporting a misleading "temporarily unavailable" after the fact.
  accessible: boolean;
  /**
   * What identifies this row, and what gets stored as the user's pick — the
   * same region can appear twice, once as our servers and once as P2P exits
   * (see shared/regionKey.ts). Older servers don't send it; the region is
   * the key there, which is exactly what it used to be.
   */
  key?: string;
  /**
   * The exit is another user's device: residential IP, their uplink's speed,
   * and a paid-plan feature (`accessible` is false on a trial). Absent from
   * older servers, where no row was ever P2P.
   */
  p2p?: boolean;
}

export interface SubscriptionLinksResponse {
  count: number;
  links: string[];
  /** Present only when a `region` was requested — see ApiClient#getSubscriptionLinks. */
  requestedRegion?: string;
  /** false means the requested region had no online node and the server fell back to all nodes. */
  requestedRegionAvailable?: boolean;
}

export interface RoutingConfigResponse {
  primaryTransport: string;
  fallbackTransport: string;
  fingerprint: string;
  backoffInitialSec: number;
  maxRetriesBeforeNodeSwitch: number;
  nodes: {
    id: number;
    publicIp: string;
    vlessPort: number;
    region: string;
    sni: string;
    // Phase 9: gRPC+Reality fallback inbound, same node/keys, different port —
    // present only for "direct" nodes (see NodeManagementService on the server).
    grpcFallbackPort: number | null;
    grpcFallbackServiceName: string | null;
  }[];
}

/**
 * Thrown (as an ApiError's message) when a session has ended and the user has
 * to sign in again — survives the IPC boundary as text, like
 * NETWORK_ERROR_PREFIX in ipc.ts, so the renderer can tell it from "no
 * session yet" (which it answers with a silent device login).
 */
export const SESSION_EXPIRED = 'SESSION_EXPIRED';

/** How long a remembered ping target stays usable. */
const PING_TARGET_MAX_AGE_MS = 24 * 3600 * 1000;

export class ApiError extends Error {
  constructor(
    public readonly httpCode: number,
    message: string
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * REST client for the server API surface this app uses (server/.../
 * controller: AuthController, UserController, ClientController) — same
 * contract as web/src/api.ts and the Android ApiClient.
 */
export class ApiClient {
  private readonly hostRotation: ApiHostRotation;
  private lastRenewAttemptMs = 0;
  /** In-flight renewal/recovery, shared so parallel calls do not each start one. */
  private sessionWork: Promise<string | null> | null = null;
  private readonly sessionExpiredListeners = new Set<() => void>();

  /**
   * @param baseUrls primary host first, then backup domains (Phase 10:
   *   "резервные домены API"). Defaults to VPN_API_BASE_URL plus
   *   VPN_API_BASE_URLS_BACKUP (comma-separated), or the placeholder host.
   */
  constructor(private readonly tokenStore: TokenStore, baseUrls: string[] = hostsFromEnv()) {
    this.hostRotation = new ApiHostRotation(baseUrls.map((url) => (url.endsWith('/') ? url : `${url}/`)));
  }

  /**
   * Attaches this install's deviceUuid so the server can fold any guest/
   * trial account still sitting on this device into the account being
   * signed into, instead of leaving it orphaned — see GuestMergeService.
   * Harmless if there's no such guest account: the server just no-ops.
   */
  async login(email: string, password: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>(
      'api/v1/auth/login',
      { email, password, deviceUuid: this.tokenStore.getOrCreateDeviceUuid() },
      false
    );
    this.tokenStore.saveSession(resp.token, resp.userId, false);
    return resp;
  }

  async register(email: string, password: string, referralCode?: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>('api/v1/auth/register', { email, password, referralCode }, false);
    this.tokenStore.saveSession(resp.token, resp.userId, false);
    return resp;
  }

  /**
   * No-signup trial entry point: logs in with this install's stable device
   * UUID (see TokenStore#getOrCreateDeviceUuid), which the server
   * find-or-creates a User for and grants a trial subscription to on first
   * call (no real expiry date anymore, only a traffic cap — see
   * BillingService.NO_EXPIRY_DAYS server-side) — idempotent on subsequent
   * calls (same account, no extra trial). Lets a
   * fresh install land on the connect screen without ever seeing LoginPage.
   */
  async deviceLogin(deviceUuid: string, referralCode?: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>('api/v1/auth/device', { deviceUuid, referralCode }, false);
    this.tokenStore.saveSession(resp.token, resp.userId, true);
    return resp;
  }

  /**
   * Converts the currently-signed-in guest/device-trial account into a
   * real, credentialed one in place — same user id, balance, and active
   * trial subscription, just adding an email+password so it survives
   * logout/reinstall. The register-time counterpart to login()'s merge
   * above; must be called while still holding the guest's token (`true` ->
   * sends the current Authorization header), not after switching away from
   * it. See POST /api/v1/auth/upgrade.
   */
  async upgradeGuest(email: string, password: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>('api/v1/auth/upgrade', { email, password }, true);
    this.tokenStore.saveSession(resp.token, resp.userId, false);
    return resp;
  }

  /**
   * @param idToken Google ID token (JWT) obtained via the desktop OAuth loopback flow — see main/auth/googleOAuth.ts.
   * Also attaches this install's deviceUuid, same as login() above, so a
   * guest/trial account still sitting on this device gets folded into
   * whichever Google account this signs into instead of orphaned — see
   * GuestMergeService. Applies whether that Google account already existed
   * or gets created fresh by this very call.
   */
  async googleAuth(idToken: string, referralCode?: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>(
      'api/v1/auth/google',
      { idToken, referralCode, deviceUuid: this.tokenStore.getOrCreateDeviceUuid() },
      false
    );
    this.tokenStore.saveSession(resp.token, resp.userId, false);
    return resp;
  }

  /** Called when a session ends for good and the user has to sign in again. Returns an unsubscribe. */
  onSessionExpired(listener: () => void): () => void {
    this.sessionExpiredListeners.add(listener);
    return () => this.sessionExpiredListeners.delete(listener);
  }

  getOrCreateDeviceUuid(): string {
    return this.tokenStore.getOrCreateDeviceUuid();
  }

  async getProfile(): Promise<UserProfile> {
    const profile = await this.get<UserProfile>('api/v1/user/profile');
    // Authoritative, and what decides how an expired session is handled.
    if (profile) this.tokenStore.setDeviceAccount(Boolean(profile.isGuest));
    return profile;
  }

  async getDevices(): Promise<DeviceDto[]> {
    return this.get<DeviceDto[]>('api/v1/user/devices');
  }

  addDevice(deviceName: string, platform: string): Promise<{ deviceId: number; deviceName: string; platform: string }> {
    return this.post('api/v1/user/devices', { deviceName, platform }, true);
  }

  async deleteDevice(deviceId: number): Promise<void> {
    await this.request(`api/v1/user/devices/${deviceId}`, { method: 'DELETE' }, true);
  }

  /**
   * @returns false on a 404 (revoked elsewhere, or never registered) —
   * caller should fall back to addDevice(). Any other failure (network, 5xx)
   * rejects so a transient outage isn't misread as "please re-register".
   */
  async touchDevice(deviceId: number): Promise<boolean> {
    try {
      await this.post(`api/v1/user/devices/${deviceId}/touch`, {}, true);
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.httpCode === 404) return false;
      throw e;
    }
  }

  /**
   * @param region optional (from getRegions()) — restricts the returned links
   *   to that region's online nodes; the server falls back to every online
   *   node (today's "auto" behavior) if the region currently has none, and
   *   says so via `requestedRegionAvailable: false` in the response.
   */
  async getSubscriptionLinks(region?: string | null): Promise<SubscriptionLinksResponse> {
    const query = region ? `?region=${encodeURIComponent(region)}` : '';
    const resp = await this.get<SubscriptionLinksResponse>(`api/v1/user/subscription/links${query}`);
    this.rememberPingTargets(resp.links ?? []);
    return { count: resp.count ?? 0, links: resp.links ?? [], requestedRegion: resp.requestedRegion, requestedRegionAvailable: resp.requestedRegionAvailable };
  }

  /**
   * Notes one node per region from links fetched anyway (every connect does),
   * so a latency measurement needs no links request of its own — which the
   * server also counts against the account's anti-enumeration budget.
   */
  private rememberPingTargets(links: string[]): void {
    const now = Date.now();
    const seen = new Set<string>();
    for (const link of links) {
      try {
        const uri = parseVlessUri(link);
        const region = uri.remark ? regionLabel(uri.remark) : null;
        if (region && !seen.has(region)) {
          seen.add(region);
          this.tokenStore.savePingTarget(region, uri.host, uri.port, now);
        }
      } catch {
        // a malformed link just isn't remembered
      }
    }
  }

  /** Regions with at least one online node this user's subscription can reach, each with a rough load indicator. */
  async getRegions(): Promise<RegionInfo[]> {
    const resp = await this.get<{ regions: RegionInfo[] }>('api/v1/user/regions');
    return resp.regions ?? [];
  }

  /**
   * Latency to the one region the user actually picked, or null when it
   * cannot be measured (no selection, no link for it, unreachable).
   *
   * This used to measure every region whenever the Connect page mounted. A
   * "ping" here is a real TCP connection to a node's live Xray inbound, so
   * pinging the whole list cost one connection per region per mount across
   * the fleet, and those connections feed the same activeConnections the
   * region load indicator is computed from — measuring load nudged it. The
   * repo owner's call: measure only the chosen region, where the number is
   * acted on, and let the list compare by load and node count instead. The
   * Android client does the same (ApiClient#pingSelectedRegion).
   */
  async pingSelectedRegion(region: string | null): Promise<number | null> {
    if (!region) return null;
    // A P2P exit has nothing to measure this way: a peer is reached over
    // WebRTC and the directory deliberately never hands out its address
    // (P2pRelayDirectory#describe). Null renders as "no figure", which is
    // honest — unlike a latency borrowed from some server in that country.
    if (isP2pRegionKey(region)) return null;
    try {
      let target = this.tokenStore.getPingTarget(region, Date.now(), PING_TARGET_MAX_AGE_MS);
      if (!target) {
        // Not learnt yet (never connected there): fetch once, which also
        // remembers it. Only a node genuinely in this region: the server
        // falls back to any online node when the asked-for one has none, and
        // reporting that node's latency as this region's would be a plain lie
        // (see firstLinkForRegion).
        const resp = await this.getSubscriptionLinks(region);
        const node = firstLinkForRegion(resp.links || [], region);
        if (!node) return null;
        target = { host: node.host, port: node.port };
      }
      return await pingTcp(target.host, target.port, 2000);
    } catch (e) {
      console.warn('Failed to ping the selected region:', e);
    }
    return null;
  }

  /** Persisted per-install region preference (null = "auto"/best-available, today's implicit behavior). */
  getSelectedRegion(): string | null {
    return this.tokenStore.getSelectedRegion();
  }

  setSelectedRegion(region: string | null): void {
    this.tokenStore.saveSelectedRegion(region);
  }

  getRoutingConfig(operator: string | null, region: string | null): Promise<RoutingConfigResponse> {
    const params = new URLSearchParams();
    if (operator) params.set('operator', operator);
    if (region) params.set('region', region);
    const query = params.toString();
    return this.get<RoutingConfigResponse>(`api/v1/client/config${query ? `?${query}` : ''}`);
  }

  async submitTelemetry(
    nodeId: number | null,
    operator: string | null,
    region: string | null,
    transport: string,
    connectTimeMs: number,
    failureCount: number,
    isWhitelistSuspected: boolean
  ): Promise<void> {
    try {
      await this.post(
        'api/v1/client/telemetry',
        { nodeId, operator, region, transport, connectTimeMs, failureCount, isWhitelistSuspected },
        true
      );
    } catch {
      // Best-effort; never let telemetry failures break the connection flow.
    }
  }

  /**
   * Mints a short-lived, single-use code the web dashboard can exchange for
   * a real session JWT (see WEB_HANDOFF_RESEARCH.md) — lets a user land on
   * the web dashboard already signed in instead of hitting its login page.
   * `webUrl` is server-built (vpn.public.web-base-url) so this client never
   * needs its own copy of that config.
   */
  requestWebHandoff(): Promise<{ code: string; webUrl: string; expiresInSeconds: number }> {
    return this.post('api/v1/auth/web-handoff', {}, true);
  }

  /**
   * Best-effort revokes this install's own registered Device row before
   * wiping the local session — otherwise it lingers in the user's device
   * list (and against their device-limit count) until the 30-day
   * inactivity window ages it out on its own (see
   * DeviceManagementService#DEVICE_ACTIVE_WINDOW_DAYS on the server). A
   * failed revoke (offline, already gone) must not block logout itself.
   */
  async logout(): Promise<void> {
    const deviceId = this.tokenStore.getDeviceId();
    if (deviceId) {
      try {
        await this.deleteDevice(deviceId);
      } catch {
        // Best-effort — proceed with clearing the local session regardless.
      }
    }
    this.tokenStore.clear();
  }

  getDeviceId(): number | null {
    return this.tokenStore.getDeviceId();
  }

  /** docs/research/P2P_RELAY_FEASIBILITY.md §8.6 — required once before a p2p bootstrap token can ever be minted. */
  acceptP2pRelayTerms(): Promise<{ acceptedAt: string }> {
    return this.post('api/v1/user/p2p/accept-terms', {}, true);
  }

  getP2pRelayStatus(): Promise<{
    termsAccepted: boolean;
    isGuest: boolean;
    bytesCreditedToday: number;
    dailyCapBytes: number;
    remainingCapBytesToday: number;
  }> {
    return this.get('api/v1/user/p2p/status');
  }

  /** Mints a fresh, user-bound, short-lived (1h) bootstrap token for this device's own relay agent to register with. */
  createP2pBootstrapToken(): Promise<{ token: string; expiresAt: string }> {
    return this.post('api/v1/user/p2p/bootstrap-token', {}, true);
  }

  /**
   * host:port for the server's gRPC control-plane (AgentRegistrationService/
   * AgentStreamService, see agent/src/config.ts's identical SERVER_GRPC_URL
   * env var for the VPS-agent equivalent) — derived from the current REST
   * base URL's hostname since both live on the same server, unless
   * VPN_API_GRPC_URL overrides it explicitly (useful for local dev, where
   * the REST port is on a Vite dev proxy but gRPC isn't).
   */
  getGrpcTarget(): string {
    if (process.env.VPN_API_GRPC_URL) return process.env.VPN_API_GRPC_URL;
    const hostname = new URL(this.hostRotation.current()).hostname;
    return `${hostname}:9090`;
  }

  /**
   * Origin of the web dashboard/landing page (same host the server itself
   * serves via copyWebDist — there's no separate web deployment), for
   * building a link to a page that only exists there, like the P2P relay
   * terms page. That page is hash-routed (web/src/App.tsx's #p2p-terms,
   * same convention as #admin — this SPA has no server-side path routing),
   * so callers append "#p2p-terms" themselves rather than this method
   * assuming any one specific page.
   */
  getWebOrigin(): string {
    return new URL(this.hostRotation.current()).origin;
  }

  saveDeviceId(deviceId: number): void {
    this.tokenStore.saveDeviceId(deviceId);
  }

  /**
   * Relay peers this account can currently connect *through*
   * (GET /api/v1/user/p2p/relays). A relay is a path to a node, not an exit —
   * see main/p2p/relayClient.ts.
   */
  async getP2pRelays(): Promise<{ nodeId: number; region: string | null; activeConnections: number }[]> {
    const resp = await this.get<{ relays: { nodeId: number; region: string | null; activeConnections: number }[] }>(
      'api/v1/user/p2p/relays'
    );
    return resp.relays ?? [];
  }

  /**
   * Peers this account may use as an *exit* for the given region
   * (GET /api/v1/user/p2p/exits) — the user picked a P2P row in the region
   * list, and these are the devices that can carry it. Empty on a trial plan:
   * P2P exits are a paid-plan feature and the row is shown locked there.
   */
  async getP2pExits(region: string): Promise<{ nodeId: number; region: string | null; activeConnections: number }[]> {
    const resp = await this.get<{ exits: { nodeId: number; region: string | null; activeConnections: number }[] }>(
      `api/v1/user/p2p/exits?region=${encodeURIComponent(region)}`
    );
    return resp.exits ?? [];
  }

  /** One signaling payload on its way to a relay; the relay's own replies come from pollP2pSignals. */
  async sendP2pSignal(nodeId: number, sessionId: string, payloadBase64: string): Promise<void> {
    await this.post<unknown>(`api/v1/user/p2p/nodes/${nodeId}/signal`, { sessionId, payloadBase64 }, true);
  }

  /** Long-polls for the relay's next signal. Null means nothing arrived in the wait, which is ordinary. */
  async pollP2pSignals(sessionId: string, waitMs: number): Promise<string | null> {
    const resp = await this.get<{ payloadBase64: string | null }>(
      `api/v1/user/p2p/sessions/${encodeURIComponent(sessionId)}/signals?waitMs=${waitMs}`
    );
    return resp?.payloadBase64 ?? null;
  }

  async closeP2pSession(sessionId: string): Promise<void> {
    await this.request<unknown>(`api/v1/user/p2p/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }, true);
  }

  /**
   * The client half of the dual traffic report that pays the peer's owner.
   * {@code exit} tells the server these bytes went out to the internet from
   * the peer rather than to a node of ours, which is what makes them count
   * against this account's own quota — nothing else meters an exit session.
   */
  async reportP2pSessionTraffic(
    sessionId: string,
    nodeId: number,
    bytesRelayed: number,
    exit = false
  ): Promise<void> {
    await this.post<unknown>(
      `api/v1/user/p2p/sessions/${encodeURIComponent(sessionId)}/traffic-report`,
      { nodeId, bytesRelayed, exit },
      true
    );
  }

  /**
   * Ships collected failures (see main/diagnostics.ts). Unauthenticated on
   * purpose — "cannot obtain a token" is one of the failures worth reporting,
   * and the server bounds this channel itself rather than trusting callers.
   */
  postDiagnostics(payload: unknown): Promise<unknown> {
    return this.post<unknown>('api/v1/client/diagnostics', payload, false);
  }

  private get<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: 'GET' }, true);
  }

  private post<T>(path: string, body: unknown, auth: boolean): Promise<T> {
    return this.request<T>(path, { method: 'POST', body: JSON.stringify(body) }, auth);
  }

  /**
   * On a network-level failure (DNS/connect/timeout — consistent with the
   * primary API domain being blocked or poisoned), retries against the next
   * configured backup domain before giving up. A real HTTP response, even an
   * error one (4xx/5xx), is never retried against another host — that's an
   * actual answer from the actual server, not a connectivity problem.
   */
  private async request<T>(path: string, init: RequestInit, auth: boolean): Promise<T> {
    const token = auth ? this.tokenStore.getToken() : null;
    if (!token) return this.send<T>(path, init, null);

    const current = (await this.renewIfNearExpiry(token)) ?? token;
    try {
      return await this.send<T>(path, init, current);
    } catch (e) {
      if (!(e instanceof ApiError) || e.httpCode !== 401) throw e;
      const recovered = await this.recoverSession(current);
      if (!recovered) throw new ApiError(401, SESSION_EXPIRED);
      return this.send<T>(path, init, recovered);
    }
  }

  /**
   * Swaps a token close to expiry for a fresh one before it is used, so a
   * client in regular use is never signed out by the clock. Best-effort: on
   * failure the current token is still good for days.
   */
  private async renewIfNearExpiry(token: string): Promise<string | null> {
    const now = Date.now();
    if (!shouldRenew(token, now, this.lastRenewAttemptMs)) return null;
    if (!this.sessionWork) {
      this.lastRenewAttemptMs = now;
      this.sessionWork = this.send<AuthResponse>('api/v1/auth/refresh', { method: 'POST', body: '{}' }, token)
        .then((resp) => {
          if (!resp?.token) return null;
          this.tokenStore.replaceToken(resp.token);
          return resp.token;
        })
        .catch(() => null)
        .finally(() => {
          this.sessionWork = null;
        });
    }
    return this.sessionWork;
  }

  /**
   * After a 401: a device-trial account is signed straight back in (nothing
   * to ask the user for); anyone else's session is over — the dead token is
   * dropped and listeners told, rather than every later call failing with an
   * error nobody explains. Never creates a guest account for a registered
   * user: that would silently hide their plan behind a fresh trial.
   * @returns a token to retry with, or null.
   */
  private async recoverSession(rejectedToken: string): Promise<string | null> {
    // A renewal or recovery already under way decides it for this call too.
    if (this.sessionWork) await this.sessionWork;
    const current = this.tokenStore.getToken();
    if (current && current !== rejectedToken) return current; // already recovered by a parallel call
    if (!this.sessionWork) {
      this.sessionWork = (async () => {
        if (this.tokenStore.isDeviceAccount() === true) {
          try {
            return (await this.deviceLogin(this.tokenStore.getOrCreateDeviceUuid())).token;
          } catch {
            // e.g. this device now belongs to a registered account — fall through
          }
        }
        this.tokenStore.clearToken();
        for (const listener of this.sessionExpiredListeners) listener();
        return null;
      })().finally(() => {
        this.sessionWork = null;
      });
    }
    return this.sessionWork;
  }

  private async send<T>(path: string, init: RequestInit, token: string | null): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;

    let lastError: unknown;
    for (let attempt = 0; attempt < this.hostRotation.size(); attempt++) {
      const host = attempt === 0 ? this.hostRotation.current() : this.hostRotation.advance();
      try {
        const response = await fetch(host + path, { ...init, headers });
        const text = await response.text();
        if (!response.ok) {
          throw new ApiError(response.status, extractError(text));
        }
        return text ? (JSON.parse(text) as T) : (undefined as T);
      } catch (e) {
        if (e instanceof ApiError) throw e; // real server response — don't rotate hosts
        lastError = e;
      }
    }
    throw lastError;
  }
}

function hostsFromEnv(): string[] {
  // NOTE: this used to check `process.env.NODE_ENV !== 'production'`, but
  // Electron never sets NODE_ENV for a packaged app (and electron-builder
  // doesn't either) — so a real, installed build defaulted to the dev-only
  // `http://localhost:8080/` base URL for every user who didn't happen to
  // have NODE_ENV=production in their shell, instead of the real production
  // domain. `app.isPackaged` is the correct, already-used-elsewhere
  // (autoUpdater.ts, tray.ts, binaryManager.ts) way to tell a packaged build
  // from a dev run.
  const isDev = !app.isPackaged;
  const defaultUrl = isDev ? DEV_DEFAULT_BASE_URL : DEFAULT_BASE_URL;
  const hosts = [process.env.VPN_API_BASE_URL || defaultUrl];
  const backups = process.env.VPN_API_BASE_URLS_BACKUP;
  if (backups) {
    hosts.push(...backups.split(',').map((h) => h.trim()).filter(Boolean));
  }
  return hosts;
}

function extractError(body: string): string {
  try {
    const parsed = JSON.parse(body);
    if (parsed && typeof parsed.error === 'string') return parsed.error;
  } catch {
    // not JSON
  }
  return body || 'Request failed';
}
