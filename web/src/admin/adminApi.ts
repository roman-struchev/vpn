import { getToken } from '../api';

const authHeaders = (): HeadersInit => {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
};

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/v1/admin${path}`, { ...init, headers: authHeaders() });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `Admin request failed: ${res.status}`);
  }
  // Some admin endpoints (e.g. nodes/{id}/sync) return a message with no useful body.
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export interface DashboardMetrics {
  totalUsers: number;
  activeSubscriptions: number;
  totalBalanceUsdt: number;
  totalTrafficUsedBytes: number;
  onlineNodes: number;
  totalNodes: number;
  /** Referral money handed out so far (15% referrer bonuses + 10% welcome bonuses). */
  totalReferralBonusesPaidUsdt: number;
  totalReferralBonusesCount: number;
  telemetryDegradation: Array<{
    operator: string;
    region: string;
    transport: string;
    totalReports: number;
    whitelistSuspected: number;
  }>;
}

export interface AdminUser {
  id: number;
  email: string;
  telegramId: string | null;
  /** Set for accounts auto-created by the desktop app's no-signup trial flow (see AuthController#deviceAuth). */
  deviceUuid: string | null;
  role: string;
  status: string;
  balanceUsdtMicro: number;
  referralCode: string;
  referredByUserId: number | null;
  createdAt: string;
  deviceCount: number;
  referralCount: number;
  /** Referral bonuses credited to this user so far, in micro-USDT. */
  referralEarningsUsdtMicro: number;
  activeSubscription?: {
    id: number;
    tariffId: string;
    currentPeriodEnd: string;
    trafficUsedBytes: number;
    trafficLimitBytes: number;
  };
}

export interface AdminNode {
  id: number;
  hostname: string;
  publicIp: string;
  status: string;
  pool: string;
  type: string;
  region: string;
  asn: string | null;
  configVersion: number;
  cpuPercent: number | null;
  cpuCount: number | null;
  memoryUsedBytes: number | null;
  memoryTotalBytes: number | null;
  activeConnections: number | null;
  totalBytesServed: number;
  lastHeartbeatAt: string | null;
  createdAt: string;
}

export interface AdminTransportPolicy {
  id?: number;
  scope: string;
  scopeValue: string;
  primaryTransport: string;
  fallbackTransport: string;
  fingerprint: string;
  backoffInitialSec: number;
  maxRetriesBeforeNodeSwitch: number;
  isActive: boolean;
}

export const adminApi = {
  getDashboard: () => req<DashboardMetrics>('/dashboard'),

  listUsers: () => req<AdminUser[]>('/users'),
  adjustBalance: (userId: number, amountMicro: number, description?: string) =>
    req(`/users/${userId}/balance`, {
      method: 'POST',
      body: JSON.stringify({ amountMicro, description }),
    }),
  setUserStatus: (userId: number, status: 'ACTIVE' | 'BLOCKED') =>
    req(`/users/${userId}/status`, { method: 'POST', body: JSON.stringify({ status }) }),
  extendSubscription: (userId: number, days: number) =>
    req(`/users/${userId}/subscription/extend`, { method: 'POST', body: JSON.stringify({ days }) }),

  listNodes: () => req<AdminNode[]>('/nodes'),
  createBootstrapToken: (pool: string, type: string, validHours: number) =>
    req<{ token: string; assignedPool: string; assignedType: string; expiresAt: string }>(
      `/nodes/bootstrap-token?pool=${pool}&type=${type}&validHours=${validHours}`,
      { method: 'POST' }
    ),
  setNodePool: (nodeId: number, pool: string) =>
    req(`/nodes/${nodeId}/pool?pool=${pool}`, { method: 'POST' }),
  setNodeStatus: (nodeId: number, status: string) =>
    req(`/nodes/${nodeId}/status?status=${status}`, { method: 'POST' }),
  forceSync: (nodeId: number) => req(`/nodes/${nodeId}/sync`, { method: 'POST' }),
  sendNodeCommand: (nodeId: number, type: string) =>
    req(`/nodes/${nodeId}/command?type=${type}`, { method: 'POST' }),

  listPolicies: () => req<AdminTransportPolicy[]>('/policies'),
  savePolicy: (policy: AdminTransportPolicy) =>
    req<AdminTransportPolicy>('/policies', { method: 'POST', body: JSON.stringify(policy) }),

  reconcileDeposit: (chain: string, amountMicro: number, txHash?: string, depositAddress?: string) =>
    req<{ status: string; message?: string; invoiceId?: number; userId?: number }>('/crypto/reconcile', {
      method: 'POST',
      body: JSON.stringify({ chain, amountMicro, txHash, depositAddress }),
    }),
  enforceQuotas: () => req<{ message: string }>('/tasks/enforce-quotas', { method: 'POST' }),
};
