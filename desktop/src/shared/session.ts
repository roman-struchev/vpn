/**
 * When to renew the session token. Pure (no Electron/Node APIs) so it is
 * unit tested like the rest of shared/ — same rules as the Android client's
 * ApiClient#renewIfNearExpiry.
 */

/** Renew once the token has less than this left. */
export const RENEW_WHEN_LEFT_SEC = 7 * 24 * 3600;
/** And ask at most this often, so an unreachable server is not asked on every call. */
export const RENEW_ATTEMPT_INTERVAL_MS = 3_600_000;

/** The token's `exp` (epoch seconds) without verifying it, or null if unreadable. */
export function jwtExpiresAtSec(token: string | null | undefined): number | null {
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length < 2) return null;
  try {
    const json = Buffer.from(parts[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf-8');
    const exp = (JSON.parse(json) as { exp?: unknown }).exp;
    return typeof exp === 'number' ? exp : null;
  } catch {
    return null;
  }
}

/**
 * True when a still-valid token is close enough to expiry to renew now. An
 * already-expired one is not "renewable" — /auth/refresh would only answer
 * 401, which the caller's 401 handling deals with.
 */
export function shouldRenew(token: string | null, nowMs: number, lastAttemptMs: number): boolean {
  const exp = jwtExpiresAtSec(token);
  if (exp === null) return false;
  const leftSec = exp - Math.floor(nowMs / 1000);
  if (leftSec <= 0 || leftSec > RENEW_WHEN_LEFT_SEC) return false;
  return nowMs - lastAttemptMs >= RENEW_ATTEMPT_INTERVAL_MS;
}
