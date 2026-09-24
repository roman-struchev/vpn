import { UserProfile, Tariff, Device, CryptoInvoice, InvoiceHistoryEntry, BalanceHistoryEntry, P2pRelayStatus } from './types';

const TOKEN_KEY = 'vpn_auth_token';

export const getToken = (): string | null => localStorage.getItem(TOKEN_KEY);
export const setToken = (token: string) => localStorage.setItem(TOKEN_KEY, token);
export const removeToken = () => localStorage.removeItem(TOKEN_KEY);

/** Renew the session once the token has less than this left. */
const RENEW_WHEN_LEFT_SEC = 7 * 24 * 3600;

/** The token's exp (epoch seconds) without verifying it, or null if unreadable. */
const tokenExpiresAtSec = (token: string): number | null => {
  try {
    const part = token.split('.')[1];
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/'));
    const exp = JSON.parse(json).exp;
    return typeof exp === 'number' ? exp : null;
  } catch {
    return null;
  }
};

const getAuthHeaders = (): HeadersInit => {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
};

/** The server's `{"error": "..."}` message, else a fallback. */
async function errorOf(res: Response, fallback: string): Promise<Error> {
  const body = await res.json().catch(() => null);
  return new Error((body && body.error) || fallback);
}

export const api = {
  /** Always resolves the same way: the server doesn't say whether the address exists. */
  async requestPasswordReset(email: string): Promise<void> {
    const res = await fetch('/api/v1/auth/password-reset/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email }),
    });
    if (!res.ok) throw await errorOf(res, 'Request failed');
  },

  async confirmPasswordReset(email: string, code: string, newPassword: string) {
    const res = await fetch('/api/v1/auth/password-reset/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, code, newPassword }),
    });
    if (!res.ok) throw await errorOf(res, 'Reset failed');
    const data = await res.json();
    setToken(data.token);
    return data;
  },

  /** A one-time code for signing in to the Android/desktop app as this account. */
  async getAppLoginCode(): Promise<{ code: string; expiresInSeconds: number }> {
    const res = await fetch('/api/v1/user/app-login-code', { method: 'POST', headers: getAuthHeaders() });
    if (!res.ok) throw await errorOf(res, 'Could not get a code');
    return res.json();
  },

  /** Change the password, or add email + password to a Telegram/Google account. */
  async setCredentials(email: string | null, currentPassword: string | null, newPassword: string) {
    const res = await fetch('/api/v1/user/credentials', {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify({ email, currentPassword, newPassword }),
    });
    if (!res.ok) throw await errorOf(res, 'Could not save');
    const data = await res.json();
    setToken(data.token);
    return data;
  },

  async deleteAccount(): Promise<void> {
    const res = await fetch('/api/v1/user/account', {
      method: 'DELETE',
      headers: getAuthHeaders(),
      body: JSON.stringify({ confirm: true }),
    });
    if (!res.ok) throw await errorOf(res, 'Could not delete the account');
  },

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
    // Only a 401 means the session is gone. Anything else (a 5xx, a proxy
    // hiccup) used to be reported the same way and signed the user out.
    if (res.status === 401) throw new Error('Unauthorized');
    if (!res.ok) throw new Error('Failed to load profile');
    return res.json();
  },

  /**
   * Swaps a token close to expiry for a fresh one, so someone who keeps
   * coming back is never signed out by the clock. Best-effort: on failure
   * the current token is still good for days.
   */
  async renewSessionIfNeeded(): Promise<void> {
    const token = getToken();
    if (!token) return;
    const exp = tokenExpiresAtSec(token);
    const leftSec = exp === null ? null : exp - Math.floor(Date.now() / 1000);
    if (leftSec === null || leftSec <= 0 || leftSec > RENEW_WHEN_LEFT_SEC) return;
    try {
      const res = await fetch('/api/v1/auth/refresh', { method: 'POST', headers: getAuthHeaders() });
      if (!res.ok) return;
      const data = await res.json();
      if (data?.token) setToken(data.token);
    } catch {
      // keep the current token
    }
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

  async applyPromoCode(code: string): Promise<{ success: boolean; bonusUsdtMicro: number; newBalanceUsdtMicro: number; code: string }> {
    const res = await fetch('/api/v1/user/promo/apply', {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify({ code }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: 'Failed to apply promo code' }));
      throw new Error(err.error || 'Failed to apply promo code');
    }
    return res.json();
  },


  /** Every actual balance-ledger movement -- deposits, subscription debits,
   *  referral bonuses, refunds, manual adjustments -- not just crypto deposit
   *  invoices like getInvoiceHistory() above. See DashboardView's merged
   *  billing history card. */
  async getBalanceHistory(): Promise<BalanceHistoryEntry[]> {
    const res = await fetch('/api/v1/user/balance-history', {
      headers: getAuthHeaders(),
    });
    if (!res.ok) throw new Error('Failed to load balance history');
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

  /** Schedules a cheaper plan from the end of the paid period; null cancels it. */
  async scheduleNextTariff(tariffId: string | null): Promise<any> {
    const res = await fetch('/api/v1/user/billing/next-tariff', {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify({ tariffId }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: 'Plan change failed' }));
      throw new Error(err.error || 'Plan change failed');
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
   * P2P relay consent/credit status (docs/research/P2P_RELAY_FEASIBILITY.md
   * §8.6) -- actually turning relay mode ON only happens in the native
   * desktop/Android clients (phases 2/3, built separately); the web
   * dashboard only surfaces consent + read-only stats.
   */
  async getP2pStatus(): Promise<P2pRelayStatus> {
    const res = await fetch('/api/v1/user/p2p/status', {
      headers: getAuthHeaders(),
    });
    if (!res.ok) throw new Error('Failed to load P2P relay status');
    return res.json();
  },

  async acceptP2pTerms(): Promise<{ acceptedAt: string }> {
    const res = await fetch('/api/v1/user/p2p/accept-terms', {
      method: 'POST',
      headers: getAuthHeaders(),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: 'Failed to accept terms' }));
      throw new Error(err.error || 'Failed to accept terms');
    }
    return res.json();
  },
};
