import { UserProfile, Tariff, Device, CryptoInvoice, InvoiceHistoryEntry } from './types';

const TOKEN_KEY = 'vpn_auth_token';

export const getToken = (): string | null => localStorage.getItem(TOKEN_KEY);
export const setToken = (token: string) => localStorage.setItem(TOKEN_KEY, token);
export const removeToken = () => localStorage.removeItem(TOKEN_KEY);

const getAuthHeaders = (): HeadersInit => {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
};

export const api = {
  async login(email: string, passwordHash: string) {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password: passwordHash }),
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    setToken(data.token);
    return data;
  },

  async register(email: string, passwordHash: string, referralCode?: string) {
    const res = await fetch('/api/v1/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password: passwordHash, referralCode }),
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    setToken(data.token);
    return data;
  },

  async telegramAuth(initData: string, referralCode?: string) {
    const res = await fetch('/api/v1/auth/telegram', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ initData, referralCode }),
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    setToken(data.token);
    return data;
  },

  async getProfile(): Promise<UserProfile> {
    const res = await fetch('/api/v1/user/profile', {
      headers: getAuthHeaders(),
    });
    if (!res.ok) throw new Error('Unauthorized');
    return res.json();
  },

  async getTariffs(): Promise<Tariff[]> {
    const res = await fetch('/api/v1/user/tariffs', {
      headers: getAuthHeaders(),
    });
    if (!res.ok) throw new Error('Failed to load tariffs');
    return res.json();
  },

  async getDevices(): Promise<Device[]> {
    const res = await fetch('/api/v1/user/devices', {
      headers: getAuthHeaders(),
    });
    if (!res.ok) throw new Error('Failed to load devices');
    return res.json();
  },

  async deleteDevice(deviceId: number): Promise<void> {
    const res = await fetch(`/api/v1/user/devices/${deviceId}`, {
      method: 'DELETE',
      headers: getAuthHeaders(),
    });
    if (!res.ok) throw new Error('Failed to revoke device');
  },

  async createCryptoInvoice(chain: string, baseAmountMicro: number): Promise<CryptoInvoice> {
    const res = await fetch('/api/v1/user/billing/invoice', {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify({ chain, baseAmountUsdtMicro: baseAmountMicro }),
    });
    if (!res.ok) throw new Error('Failed to create crypto invoice');
    return res.json();
  },

  async getInvoiceHistory(): Promise<InvoiceHistoryEntry[]> {
    const res = await fetch('/api/v1/user/invoices', {
      headers: getAuthHeaders(),
    });
    if (!res.ok) throw new Error('Failed to load invoice history');
    return res.json();
  },

  async claimTx(chain: string, txHash: string, amountMicro: number): Promise<any> {
    const res = await fetch('/api/v1/user/billing/claim-tx', {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify({ chain, txHash, amountMicro }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: 'Claim failed' }));
      throw new Error(err.error || 'Claim failed');
    }
    return res.json();
  },

  async purchaseSubscription(tariffId: string, isAnnual: boolean): Promise<any> {
    const res = await fetch('/api/v1/user/billing/purchase', {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify({ tariffId, isAnnual }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: 'Purchase failed' }));
      throw new Error(err.error || 'Purchase failed');
    }
    return res.json();
  },

  async getSubscriptionLinks(): Promise<string[]> {
    const res = await fetch('/api/v1/user/subscription/links', {
      headers: getAuthHeaders(),
    });
    if (!res.ok) return [];
    const data = await res.json();
    return data.links || [];
  },
};
