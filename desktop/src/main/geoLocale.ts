const LOOKUP_TIMEOUT_MS = 3000;

/**
 * One-shot best-effort check of whether this machine's public IP is
 * currently Russian — meant to be called once, before the VPN ever connects,
 * so it reflects the user's real location rather than a tunnel exit (see
 * TokenStore#getOriginalIpIsRussia, which caches the result so this never
 * runs again after the first successful check). Same free-tier providers as
 * scripts/install-node.sh's geo-IP detection, same fallback order.
 *
 * Returns null (not false) on failure/timeout, distinct from a confirmed
 * "not Russia" — the caller should leave the result uncached on null so a
 * flaky first launch gets retried next time instead of being stuck wrong.
 */
export async function isPublicIpRussian(): Promise<boolean | null> {
  const viaIpinfo = await lookup('https://ipinfo.io/json', (json) => json.country);
  if (viaIpinfo !== null) return viaIpinfo === 'RU';

  const viaIpApi = await lookup('http://ip-api.com/json', (json) => json.countryCode);
  if (viaIpApi !== null) return viaIpApi === 'RU';

  return null;
}

async function lookup(url: string, extractCountryCode: (json: any) => unknown): Promise<string | null> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), LOOKUP_TIMEOUT_MS);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) return null;
    const json = await response.json();
    const code = extractCountryCode(json);
    return typeof code === 'string' && code.length > 0 ? code.toUpperCase() : null;
  } catch {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}
