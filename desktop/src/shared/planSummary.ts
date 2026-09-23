/**
 * What the account page says about the user's plan, from the two things the
 * server returns separately: the subscription (GET /user/profile — traffic,
 * expiry and a bare tariffId) and the catalogue (GET /user/tariffs — name,
 * price, device allowance). The same derivation as the Android app's
 * billing/PlanSummary.java, so both apps describe a plan the same way.
 *
 * Pure: the parts worth pinning down are the fallbacks (an unknown tariff id,
 * no subscription, a zero traffic limit, a catalogue that failed to load).
 */

export interface PlanSubscription {
  tariffId: string;
  trafficUsedBytes: number;
  trafficLimitBytes: number;
  expiresAt: string;
  noExpiry?: boolean;
}

export interface PlanTariff {
  id: string;
  name: string;
  monthlyPriceUsdtMicro: number;
  maxDevices: number;
}

export interface PlanSummary {
  hasSubscription: boolean;
  tariffId: string | null;
  /** The catalogue's name, else the id capitalised; null only without a subscription. */
  planName: string | null;
  monthlyPriceUsdt: number;
  isFree: boolean;
  /** Null when the catalogue could not be resolved — the line is then left out. */
  maxDevices: number | null;
  trafficUsedBytes: number;
  trafficLimitBytes: number;
  /** 0–100; an unlimited or unknown quota reads as 0. */
  trafficPercent: number;
  /** Null when the plan has no expiry. */
  expiresAt: string | null;
}

const NONE: PlanSummary = {
  hasSubscription: false,
  tariffId: null,
  planName: null,
  monthlyPriceUsdt: 0,
  isFree: true,
  maxDevices: null,
  trafficUsedBytes: 0,
  trafficLimitBytes: 0,
  trafficPercent: 0,
  expiresAt: null,
};

export function planSummary(
  profile: { hasActiveSubscription: boolean; subscription?: PlanSubscription | null } | null | undefined,
  tariffs: PlanTariff[] | null | undefined,
): PlanSummary {
  const sub = profile?.hasActiveSubscription ? profile.subscription : null;
  if (!sub || !sub.tariffId) return NONE;

  const tariff = tariffs?.find((tf) => tf.id.toLowerCase() === sub.tariffId.toLowerCase()) ?? null;
  const monthlyPriceUsdt = tariff ? tariff.monthlyPriceUsdtMicro / 1_000_000 : 0;
  const trafficPercent =
    sub.trafficLimitBytes > 0
      ? Math.max(0, Math.min(100, Math.round((100 * sub.trafficUsedBytes) / sub.trafficLimitBytes)))
      : 0;

  return {
    hasSubscription: true,
    tariffId: sub.tariffId,
    planName: tariff?.name?.trim() ? tariff.name : sub.tariffId.charAt(0).toUpperCase() + sub.tariffId.slice(1),
    monthlyPriceUsdt,
    isFree: monthlyPriceUsdt <= 0,
    maxDevices: tariff ? tariff.maxDevices : null,
    trafficUsedBytes: sub.trafficUsedBytes,
    trafficLimitBytes: sub.trafficLimitBytes,
    trafficPercent,
    expiresAt: sub.noExpiry ? null : sub.expiresAt,
  };
}
