import { app } from 'electron';
import { ApiHostRotation } from '../../shared/apiHostRotation';
import { regionLabel, parseVlessUri } from '../../shared/vlessUri';
import { pingTcp } from '../vpn/pingUtil';
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
    this.tokenStore.save(resp.token, resp.userId);
    return resp;
  }

  async register(email: string, password: string, referralCode?: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>('api/v1/auth/register', { email, password, referralCode }, false);
    this.tokenStore.save(resp.token, resp.userId);
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
    this.tokenStore.save(resp.token, resp.userId);
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
    this.tokenStore.save(resp.token, resp.userId);
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
    this.tokenStore.save(resp.token, resp.userId);
    return resp;
  }

  getOrCreateDeviceUuid(): string {
    return this.tokenStore.getOrCreateDeviceUuid();
  }

  getProfile(): Promise<UserProfile> {
    return this.get<UserProfile>('api/v1/user/profile');
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
    return { count: resp.count ?? 0, links: resp.links ?? [], requestedRegion: resp.requestedRegion, requestedRegionAvailable: resp.requestedRegionAvailable };
  }

  /** Regions with at least one online node this user's subscription can reach, each with a rough load indicator. */
  async getRegions(): Promise<RegionInfo[]> {
    const resp = await this.get<{ regions: RegionInfo[] }>('api/v1/user/regions');
    return resp.regions ?? [];
  }

  /** Measures round-trip latency to each available region's primary node. */
  async pingRegions(): Promise<Record<string, number>> {
    try {
      const resp = await this.getSubscriptionLinks();
      const results: Record<string, number> = {};
      const links = resp.links || [];
      await Promise.all(
        links.map(async (link) => {
          try {
            const parsed = parseVlessUri(link);
            // Keyed by the region label the UI looks these up by (see ConnectPage's
            // `pings[r.region]`) — the raw remark also carries the node hostname, so
            // nothing ever matched and the ping indicator never appeared.
            const key = parsed.remark ? regionLabel(parsed.remark) : parsed.host;
            if (results[key] === undefined) {
              const latency = await pingTcp(parsed.host, parsed.port, 2000);
              if (latency !== null) {
                results[key] = latency;
              }
            }
          } catch {
            // ignore malformed link
          }
        })
      );
      return results;
    } catch (e) {
      console.warn('Failed to ping regions:', e);
      return {};
    }
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

  /** The client half of the dual traffic report that pays the relay's owner. */
  async reportP2pSessionTraffic(sessionId: string, nodeId: number, bytesRelayed: number): Promise<void> {
    await this.post<unknown>(
      `api/v1/user/p2p/sessions/${encodeURIComponent(sessionId)}/traffic-report`,
      { nodeId, bytesRelayed },
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
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (auth) {
      const token = this.tokenStore.getToken();
      if (token) headers.Authorization = `Bearer ${token}`;
    }

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
