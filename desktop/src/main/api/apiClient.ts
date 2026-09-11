import { ApiHostRotation } from '../../shared/apiHostRotation';
import type { TokenStore } from './tokenStore';

const DEFAULT_BASE_URL = 'https://vpn.struchev.site/';
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
  /** Ready-to-share plain web link built by the server (`<site>/?ref=CODE`). */
  referralLink?: string;
  referralTelegramLink?: string;
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

  async login(email: string, password: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>('api/v1/auth/login', { email, password }, false);
    this.tokenStore.save(resp.token, resp.userId);
    return resp;
  }

  async register(email: string, password: string, referralCode?: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>('api/v1/auth/register', { email, password, referralCode }, false);
    this.tokenStore.save(resp.token, resp.userId);
    return resp;
  }

  /** @param idToken Google ID token (JWT) obtained via the desktop OAuth loopback flow — see main/auth/googleOAuth.ts. */
  async googleAuth(idToken: string, referralCode?: string): Promise<AuthResponse> {
    const resp = await this.post<AuthResponse>('api/v1/auth/google', { idToken, referralCode }, false);
    this.tokenStore.save(resp.token, resp.userId);
    return resp;
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

  logout(): void {
    this.tokenStore.clear();
  }

  getDeviceId(): number | null {
    return this.tokenStore.getDeviceId();
  }

  saveDeviceId(deviceId: number): void {
    this.tokenStore.saveDeviceId(deviceId);
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
  const isDev = process.env.NODE_ENV !== 'production';
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
