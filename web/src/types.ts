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
  /** Optional extra channel — Telegram deep link, only useful to Telegram users. */
  referralTelegramLink?: string;
  /**
   * Whether this account already has a Telegram chat attached (telegramId set
   * server-side). A Telegram Stars top-up can only be completed inside
   * Telegram, so the dashboard's Top Up modal uses this to decide whether to
   * show the "connect Telegram" step or the Stars denomination buttons.
   */
  telegramLinked?: boolean;
  hasActiveSubscription: boolean;
  hasUsedTrial: boolean;
  subscription?: {
    id: number;
    tariffId: string;
    trafficUsedBytes: number;
    trafficLimitBytes: number;
    expiresAt: string;
  } | null;
}

export interface Tariff {
  id: string;
  name: string;
  monthlyPriceUsdtMicro: number;
  annualPriceUsdtMicro: number;
  trafficQuotaBytes: number;
  maxDevices: number;
  serverPool: string;
  isActive: boolean;
}

/**
 * GET /api/v1/user/p2p/status (docs/research/P2P_RELAY_FEASIBILITY.md §8) —
 * bytesCreditedToday/remainingCapBytesToday are a rolling sum over the last
 * 24h server-side (P2pRelayCreditRepository#sumBytesCreditedSince), not a
 * calendar-day counter that resets at midnight.
 */
export interface P2pRelayStatus {
  termsAccepted: boolean;
  isGuest: boolean;
  bytesCreditedToday: number;
  dailyCapBytes: number;
  remainingCapBytesToday: number;
}

export interface RegionInfo {
  region: string;
  nodeCount: number;
  avgCpuPercent: number | null;
  avgActiveConnections: number;
  loadLevel: 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface Device {
  id: number;
  deviceName: string;
  platform: string;
  isActive: boolean;
  createdAt: string;
  lastSeenAt?: string;
}

export interface CryptoInvoice {
  id: number;
  chain: string;
  token: string;
  expectedAmountUsdtMicro: number;
  toleranceMinMicro: number;
  toleranceMaxMicro: number;
  recipientAddress: string;
  status: string;
  expiresAt: string;
}

export interface InvoiceHistoryEntry {
  id: number;
  chain: string;
  status: string;
  expectedAmountUsdtMicro: number;
  actualAmountUsdtMicro: number | null;
  recipientAddress: string;
  toleranceMinMicro: number;
  toleranceMaxMicro: number;
  expiresAt: string;
  createdAt: string;
  paidAt: string | null;
}

/**
 * One row of the user's own balance ledger (GET /api/v1/user/balance-history)
 * -- deposits, subscription debits, referral bonuses, refunds, manual
 * adjustments. `type` matches BalanceEntry's type column server-side
 * (DEPOSIT, SUBSCRIPTION_DEBIT, REFUND, REFERRAL_BONUS,
 * REFERRAL_WELCOME_BONUS, MANUAL_ADJUSTMENT).
 */
export interface BalanceHistoryEntry {
  id: number;
  type: string;
  amountUsdtMicro: number;
  balanceAfterMicro: number;
  description: string;
  createdAt: string;
}
