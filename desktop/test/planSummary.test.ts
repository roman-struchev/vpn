import { describe, expect, it } from 'vitest';
import { planSummary } from '../src/shared/planSummary';

const tariffs = [
  { id: 'trial', name: 'Пробный', monthlyPriceUsdtMicro: 0, maxDevices: 1 },
  { id: 'pro', name: 'Pro', monthlyPriceUsdtMicro: 2_000_000, maxDevices: 5 },
];

const sub = (over: Record<string, unknown> = {}) => ({
  tariffId: 'pro',
  trafficUsedBytes: 25,
  trafficLimitBytes: 100,
  expiresAt: '2026-10-23T10:00:00Z',
  ...over,
});

describe('planSummary', () => {
  it('names and prices the plan from the catalogue', () => {
    const plan = planSummary({ hasActiveSubscription: true, subscription: sub() }, tariffs);
    expect(plan).toMatchObject({
      hasSubscription: true,
      planName: 'Pro',
      monthlyPriceUsdt: 2,
      isFree: false,
      maxDevices: 5,
      trafficPercent: 25,
      expiresAt: '2026-10-23T10:00:00Z',
    });
  });

  it('matches the tariff id case-insensitively', () => {
    expect(planSummary({ hasActiveSubscription: true, subscription: sub({ tariffId: 'PRO' }) }, tariffs).maxDevices).toBe(5);
  });

  it('falls back to the id, without an allowance, when the catalogue is missing', () => {
    const plan = planSummary({ hasActiveSubscription: true, subscription: sub() }, null);
    expect(plan.planName).toBe('Pro');
    expect(plan.maxDevices).toBeNull();
  });

  it('reads the trial as free with no expiry', () => {
    const plan = planSummary(
      { hasActiveSubscription: true, subscription: sub({ tariffId: 'trial', noExpiry: true }) },
      tariffs,
    );
    expect(plan.isFree).toBe(true);
    expect(plan.expiresAt).toBeNull();
  });

  it('never divides by a zero limit and clamps past 100', () => {
    expect(planSummary({ hasActiveSubscription: true, subscription: sub({ trafficLimitBytes: 0 }) }, tariffs).trafficPercent).toBe(0);
    expect(planSummary({ hasActiveSubscription: true, subscription: sub({ trafficUsedBytes: 150 }) }, tariffs).trafficPercent).toBe(100);
  });

  it('has no plan without an active subscription', () => {
    expect(planSummary({ hasActiveSubscription: false, subscription: sub() }, tariffs).hasSubscription).toBe(false);
    expect(planSummary(null, tariffs).hasSubscription).toBe(false);
  });
});
