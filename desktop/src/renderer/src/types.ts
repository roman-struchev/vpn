// Mirrors src/main/api/apiClient.ts response shapes (kept separate so the
// renderer's tsconfig doesn't need to reach into the main-process program).
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
   * account, not one the user consciously created. Drives whether the UI
   * shows account-management chrome (devices, logout) or a sign-in/
   * register CTA instead.
   */
  isGuest: boolean;
  hasActiveSubscription: boolean;
  /** Why no plan works right now; null/absent while one does (older servers never send it). */
  inactiveReason?: 'TRIAL_USED_UP' | 'TRAFFIC_USED_UP' | 'EXPIRED' | 'NONE' | null;
  subscription?: {
    id: number;
    tariffId: string;
    /** EXHAUSTED: out of traffic, but still the paid plan until expiresAt. */
    status?: 'ACTIVE' | 'EXHAUSTED';
    /** Annual plans: when the next month's traffic arrives. */
    trafficResetAt?: string | null;
    /** Renewed from the balance at expiresAt… */
    autoRenew?: boolean;
    /** …into this cheaper plan, if one was scheduled. */
    nextTariffId?: string | null;
    /** …for this much. */
    renewalPriceUsdtMicro?: number;
    trafficUsedBytes: number;
    trafficLimitBytes: number;
    expiresAt: string;
    /** No real end date (the server still sends a far-future expiresAt) — show "never", not a date in 2126. */
    noExpiry?: boolean;
  };
}

export interface TariffInfo {
  id: string;
  name: string;
  monthlyPriceUsdtMicro: number;
  annualPriceUsdtMicro: number;
  trafficQuotaBytes: number;
  maxDevices: number;
}

export interface RegionInfo {
  region: string;
  nodeCount: number;
  avgCpuPercent: number | null;
  avgActiveConnections: number;
  loadLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  // false = requires a higher plan than the account currently has — still
  // shown in the picker (not removed) but not selectable, see ConnectPage.
  accessible: boolean;
  /**
   * What identifies this row and gets stored as the pick — the same region
   * can appear twice, once as our servers and once as P2P exits (see
   * shared/regionKey.ts). Older servers don't send it; there the region is
   * the key, which is what it always was.
   */
  key?: string;
  /** The exit is another user's device: residential IP, their uplink, paid plans only. */
  p2p?: boolean;
}

export interface DeviceDto {
  id: number;
  deviceName: string;
  platform: string;
  isActive: boolean;
  createdAt: string;
  lastSeenAt?: string;
}
