import { UserProfile, Tariff, Device, CryptoInvoice, InvoiceHistoryEntry, RegionInfo } from './types';

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

  async googleAuth(idToken: string, referralCode?: string) {
    const res = await fetch('/api/v1/auth/google', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken, referralCode }),
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    setToken(data.token);
    return data;
  },

  /**
   * Redeems a client -> web SSO handoff code (see WEB_HANDOFF_RESEARCH.md)
   * minted by an already-authenticated desktop/Android client for a normal,
   * full-privilege session JWT — same response shape as login/register/
   * telegramAuth/googleAuth above.
   */
  async exchangeWebHandoff(code: string) {
    const res = await fetch('/api/v1/auth/web-handoff/exchange', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
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

  /**
   * Starts linking this account to a Telegram chat, so Telegram Stars
   * top-ups (only completable inside Telegram) can be attached to it. See
   * DashboardView.tsx's Top Up modal.
   */
  async createTelegramLink(): Promise<{ code: string; deepLink: string }> {
    const res = await fetch('/api/v1/user/telegram-link', {
      method: 'POST',
      headers: getAuthHeaders(),
    });
    if (!res.ok) throw new Error('Failed to create Telegram link');
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
      // The server returns error: "INSUFFICIENT_BALANCE" plus structured
      // *UsdtMicro fields (not a formatted sentence) for this specific failure,
      // so the caller can render its own localized "top up $X.XX more" message
      // instead of showing a raw internal string — see DashboardView.tsx's
      // handlePurchase / UX_REVIEW.md Quick Win #1.
      if (err.error === 'INSUFFICIENT_BALANCE') {
        throw Object.assign(new Error(err.error), {
          code: 'INSUFFICIENT_BALANCE',
          shortfallUsdtMicro: err.shortfallUsdtMicro,
          requiredUsdtMicro: err.requiredUsdtMicro,
          currentUsdtMicro: err.currentUsdtMicro,
        });
      }
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

  /**
   * Read-only informational listing (the web dashboard doesn't itself
   * establish a tunnel, so it never sends `region` — that's a desktop/Android
   * region-picker concern) — just lets a user glance at where nodes are and
   * how busy they are before switching to a native client.
   */
  async getRegions(): Promise<RegionInfo[]> {
    const res = await fetch('/api/v1/user/regions', {
      headers: getAuthHeaders(),
    });
    if (!res.ok) return [];
    const data = await res.json();
    return data.regions || [];
  },
};
