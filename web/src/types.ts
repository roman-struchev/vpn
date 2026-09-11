export interface UserProfile {
  id: number;
  email: string;
  role: string;
  balanceUsdtMicro: number;
  referralCode: string;
  /** Ready-to-share plain web link built by the server (`<site>/?ref=CODE`). */
  referralLink?: string;
  /** Optional extra channel — Telegram deep link, only useful to Telegram users. */
  referralTelegramLink?: string;
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
