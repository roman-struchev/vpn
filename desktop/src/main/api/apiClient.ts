import type { TokenStore } from './tokenStore';

const DEFAULT_BASE_URL = 'https://api.nextgenvpn.app/';

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

export interface RoutingConfigResponse {
  primaryTransport: string;
  fallbackTransport: string;
  fingerprint: string;
  backoffInitialSec: number;
  maxRetriesBeforeNodeSwitch: number;
  nodes: { id: number; publicIp: string; vlessPort: number; region: string; sni: string }[];
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
  private readonly baseUrl: string;

  constructor(private readonly tokenStore: TokenStore, baseUrl: string = process.env.VPN_API_BASE_URL || DEFAULT_BASE_URL) {
    this.baseUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
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

  async getSubscriptionLinks(): Promise<string[]> {
    const resp = await this.get<{ count: number; links: string[] }>('api/v1/user/subscription/links');
    return resp.links ?? [];
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

  private get<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: 'GET' }, true);
  }

  private post<T>(path: string, body: unknown, auth: boolean): Promise<T> {
    return this.request<T>(path, { method: 'POST', body: JSON.stringify(body) }, auth);
  }

  private async request<T>(path: string, init: RequestInit, auth: boolean): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (auth) {
      const token = this.tokenStore.getToken();
      if (token) headers.Authorization = `Bearer ${token}`;
    }

    const response = await fetch(this.baseUrl + path, { ...init, headers });
    const text = await response.text();

    if (!response.ok) {
      throw new ApiError(response.status, extractError(text));
    }
    return text ? (JSON.parse(text) as T) : (undefined as T);
  }
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
