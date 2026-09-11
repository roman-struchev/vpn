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
  subscription?: {
    id: number;
    tariffId: string;
    trafficUsedBytes: number;
    trafficLimitBytes: number;
    expiresAt: string;
  };
}

export interface RegionInfo {
  region: string;
  nodeCount: number;
  avgCpuPercent: number | null;
  avgActiveConnections: number;
  loadLevel: 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface DeviceDto {
  id: number;
  deviceName: string;
  platform: string;
  isActive: boolean;
  createdAt: string;
  lastSeenAt?: string;
}
