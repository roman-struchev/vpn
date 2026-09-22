import { describe, expect, it } from 'vitest';
import { jwtExpiresAtSec, RENEW_ATTEMPT_INTERVAL_MS, shouldRenew } from '../src/shared/session';
import { classifyFailure } from '../src/shared/failureReason';
import { classifyLoginError } from '../src/shared/loginError';

export function jwtExpiringIn(seconds: number, nowMs = Date.now()): string {
  const payload = Buffer.from(JSON.stringify({ sub: '1', exp: Math.floor(nowMs / 1000) + seconds })).toString('base64url');
  return `h.${payload}.s`;
}

describe('session renewal rules', () => {
  const now = 1_800_000_000_000;

  it('reads exp without verifying', () => {
    expect(jwtExpiresAtSec(jwtExpiringIn(100, now))).toBe(Math.floor(now / 1000) + 100);
    expect(jwtExpiresAtSec('garbage')).toBeNull();
    expect(jwtExpiresAtSec(null)).toBeNull();
  });

  it('renews a token with under a week left, not one with plenty', () => {
    expect(shouldRenew(jwtExpiringIn(2 * 24 * 3600, now), now, 0)).toBe(true);
    expect(shouldRenew(jwtExpiringIn(20 * 24 * 3600, now), now, 0)).toBe(false);
  });

  it('leaves an already expired token to the 401 path', () => {
    expect(shouldRenew(jwtExpiringIn(-5, now), now, 0)).toBe(false);
  });

  it('does not ask again within the hour after an attempt', () => {
    const token = jwtExpiringIn(2 * 24 * 3600, now);
    expect(shouldRenew(token, now, now - 1000)).toBe(false);
    expect(shouldRenew(token, now, now - RENEW_ATTEMPT_INTERVAL_MS)).toBe(true);
  });
});

describe('classifyFailure', () => {
  const api = (httpCode: number, message: string) => Object.assign(new Error(message), { httpCode });

  it('names the fix for each dead end', () => {
    expect(classifyFailure(api(401, 'SESSION_EXPIRED'))).toBe('SESSION_EXPIRED');
    expect(classifyFailure(api(400, 'Active subscription not found'))).toBe('NO_SUBSCRIPTION');
    expect(classifyFailure(api(400, 'Subscription has expired'))).toBe('NO_SUBSCRIPTION');
    expect(classifyFailure(new TypeError('fetch failed'))).toBe('NETWORK');
    expect(classifyFailure(new Error('No subscription links available for this account'))).toBe('NO_SERVERS');
    expect(classifyFailure(api(500, 'boom'))).toBe('UNKNOWN');
  });
});

describe('classifyLoginError', () => {
  it('sees through the IPC prefix to the server message', () => {
    const ipc = (m: string) => `Error invoking remote method 'auth:login': ApiError: ${m}`;
    expect(classifyLoginError(ipc('Invalid email or password'))).toBe('INVALID_CREDENTIALS');
    expect(classifyLoginError(ipc('Email already registered'))).toBe('EMAIL_TAKEN');
    expect(classifyLoginError(ipc('Password must be at least 6 characters'))).toBe('PASSWORD_TOO_SHORT');
    expect(classifyLoginError(ipc('Account is suspended or blocked'))).toBe('ACCOUNT_BLOCKED');
    expect(classifyLoginError('TypeError: fetch failed')).toBe('NETWORK');
    expect(classifyLoginError('something odd')).toBe('GENERIC');
  });
});
