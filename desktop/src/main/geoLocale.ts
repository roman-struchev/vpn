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

/**
 * Best-effort "Country" / "Country, City" label for wherever this machine's
 * current public IP geolocates to — auto-fills a p2p relay node's own
 * declared location (docs/research/P2P_RELAY_FEASIBILITY.md §8.4) the same
 * way scripts/install-node.sh auto-detects a regular VPS node's region from
 * its public IP at install time (same two free-tier providers, same
 * fallback order, same "Country, City"/"Country"/"default" format) — the
 * repo owner explicitly asked for this to work automatically, "как при
 * старте ноды" (like when a [regular] node starts up), not require the user
 * to type it in themselves.
 *
 * ip-api.com is tried FIRST here — reversed from isPublicIpRussian's own
 * provider order above — because its response includes a full country name
 * directly (`country`), unlike ipinfo.io's bare 2-letter code; this avoids
 * having to port install-node.sh's ~250-entry bash country-code-to-name
 * table into TypeScript. ipinfo.io is still a fallback, using its raw code
 * as a last-resort label when that's all that's available — the same
 * degraded fallback install-node.sh's own script falls back to.
 */
export async function detectNodeRegion(): Promise<string> {
  const viaIpApi = await lookupRegion('http://ip-api.com/json', (json) => ({
    country: typeof json.country === 'string' && json.country ? json.country : null,
    city: typeof json.city === 'string' && json.city ? json.city : null,
  }));
  if (viaIpApi) return formatRegion(viaIpApi.country, viaIpApi.city);

  const viaIpinfo = await lookupRegion('https://ipinfo.io/json', (json) => ({
    country: typeof json.country === 'string' && json.country ? json.country : null, // 2-letter code only
    city: typeof json.city === 'string' && json.city ? json.city : null,
  }));
  if (viaIpinfo) return formatRegion(viaIpinfo.country, viaIpinfo.city);

  return 'default';
}

function formatRegion(country: string | null, city: string | null): string {
  if (!country) return 'default';
  return city ? `${country}, ${city}` : country;
}

async function lookupRegion(
  url: string,
  extract: (json: any) => { country: string | null; city: string | null }
): Promise<{ country: string | null; city: string | null } | null> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), LOOKUP_TIMEOUT_MS);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) return null;
    const json = await response.json();
    const result = extract(json);
    return result.country ? result : null;
  } catch {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}
