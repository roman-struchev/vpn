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
  status?: 'ACTIVE' | 'EXHAUSTED';
  trafficResetAt?: string | null;
  autoRenew?: boolean;
  nextTariffId?: string | null;
  renewalPriceUsdtMicro?: number;
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

export type InactiveReason = 'TRIAL_USED_UP' | 'TRAFFIC_USED_UP' | 'EXPIRED' | 'NONE';

export interface PlanSummary {
  hasSubscription: boolean;
  /** Out of traffic: still the user's plan until it ends, but nothing works. */
  exhausted: boolean;
  /** Why nothing works right now; null while the plan works. */
  inactiveReason: InactiveReason | null;
  /** 90%+ of the quota used on a working plan. */
  lowTraffic: boolean;
  /** When a spent plan gets traffic again by itself (monthly reset of an annual plan, else its end). */
  refillAt: string | null;
  /** What happens at expiresAt, for a paid plan that renews: null otherwise. */
  renewal: {
    priceUsdt: number;
    /** Name of the cheaper plan it renews into, if one was scheduled. */
    nextPlanName: string | null;
    /** How much the balance is short of the renewal; 0 when covered. */
    shortfallUsdt: number;
  } | null;
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
  exhausted: false,
  inactiveReason: null,
  lowTraffic: false,
  refillAt: null,
  renewal: null,
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
  profile:
    | {
        hasActiveSubscription: boolean;
        subscription?: PlanSubscription | null;
        inactiveReason?: InactiveReason | null;
        balanceUsdtMicro?: number;
      }
    | null
    | undefined,
  tariffs: PlanTariff[] | null | undefined,
): PlanSummary {
  if (!profile) return NONE;
  const raw = profile.subscription;
  // A plan that ran out of traffic still comes back (status EXHAUSTED) and
  // is still theirs; older servers only ever sent a working one.
  const sub = raw && raw.tariffId && (profile.hasActiveSubscription || raw.status === 'EXHAUSTED') ? raw : null;
  const reason: InactiveReason | null = profile.hasActiveSubscription
    ? null
    : (profile.inactiveReason ?? (sub ? null : 'NONE'));
  if (!sub) return { ...NONE, inactiveReason: reason };

  const findTariff = (id: string | null | undefined) =>
    id ? tariffs?.find((tf) => tf.id.toLowerCase() === id.toLowerCase()) ?? null : null;
  const tariff = findTariff(sub.tariffId);
  const monthlyPriceUsdt = tariff ? tariff.monthlyPriceUsdtMicro / 1_000_000 : 0;
  const trafficPercent =
    sub.trafficLimitBytes > 0
      ? Math.max(0, Math.min(100, Math.round((100 * sub.trafficUsedBytes) / sub.trafficLimitBytes)))
      : 0;
  const exhausted = sub.status === 'EXHAUSTED';
  const expiresAt = sub.noExpiry ? null : sub.expiresAt;

  let renewal: PlanSummary['renewal'] = null;
  if (!exhausted && expiresAt && sub.autoRenew && (sub.renewalPriceUsdtMicro ?? 0) > 0) {
    const price = (sub.renewalPriceUsdtMicro ?? 0) / 1_000_000;
    const balance = (profile.balanceUsdtMicro ?? 0) / 1_000_000;
    const next = findTariff(sub.nextTariffId);
    renewal = {
      priceUsdt: price,
      nextPlanName: sub.nextTariffId ? next?.name ?? sub.nextTariffId : null,
      shortfallUsdt: Math.max(0, Math.round((price - balance) * 100) / 100),
    };
  }

  return {
    hasSubscription: true,
    exhausted,
    inactiveReason: reason,
    lowTraffic: !exhausted && sub.trafficLimitBytes > 0 && trafficPercent >= 90,
    refillAt:
      sub.trafficResetAt && expiresAt && sub.trafficResetAt < expiresAt ? sub.trafficResetAt : expiresAt,
    renewal,
    tariffId: sub.tariffId,
    planName: tariff?.name?.trim() ? tariff.name : sub.tariffId.charAt(0).toUpperCase() + sub.tariffId.slice(1),
    monthlyPriceUsdt,
    isFree: monthlyPriceUsdt <= 0,
    maxDevices: tariff ? tariff.maxDevices : null,
    trafficUsedBytes: sub.trafficUsedBytes,
    trafficLimitBytes: sub.trafficLimitBytes,
    trafficPercent,
    expiresAt,
  };
}
