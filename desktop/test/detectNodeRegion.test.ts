import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Covers geoLocale.ts's detectNodeRegion — the auto-detection this session
 * replaced a manual "type in your location" field with (the repo owner's
 * explicit call: it should work "как при старте ноды", i.e. the same
 * one-shot geo-IP lookup a regular VPS node does at install time, not
 * require the p2p-relay user to type anything in).
 */
describe('detectNodeRegion', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  function mockFetchSequence(responses: (Response | null)[]) {
    let call = 0;
    global.fetch = vi.fn(() => {
      const resp = responses[call++];
      if (!resp) return Promise.reject(new Error('network error'));
      return Promise.resolve(resp);
    }) as unknown as typeof fetch;
  }

  function jsonResponse(body: unknown): Response {
    return { ok: true, json: () => Promise.resolve(body) } as Response;
  }

  it('formats "Country, City" from ip-api.com (tried first)', async () => {
    mockFetchSequence([jsonResponse({ country: 'Germany', city: 'Berlin' })]);
    const { detectNodeRegion } = await import('../src/main/geoLocale');
    expect(await detectNodeRegion()).toBe('Germany, Berlin');
  });

  it('falls back to just "Country" when ip-api.com has no city', async () => {
    mockFetchSequence([jsonResponse({ country: 'Germany' })]);
    const { detectNodeRegion } = await import('../src/main/geoLocale');
    expect(await detectNodeRegion()).toBe('Germany');
  });

  it('falls back to ipinfo.io when ip-api.com fails, expanding its 2-letter code to the country name', async () => {
    mockFetchSequence([
      { ok: false } as Response, // ip-api.com
      jsonResponse({ country: 'RU', city: 'Moscow' }), // ipinfo.io
    ]);
    const { detectNodeRegion } = await import('../src/main/geoLocale');
    // Must match ip-api.com's/install-node.sh's full-name label — region grouping is an exact string match.
    expect(await detectNodeRegion()).toBe('Russia, Moscow');
  });

  it('returns "default" when both providers fail', async () => {
    mockFetchSequence([{ ok: false } as Response, { ok: false } as Response]);
    const { detectNodeRegion } = await import('../src/main/geoLocale');
    expect(await detectNodeRegion()).toBe('default');
  });

  it('returns "default" when both providers throw', async () => {
    mockFetchSequence([null, null]);
    const { detectNodeRegion } = await import('../src/main/geoLocale');
    expect(await detectNodeRegion()).toBe('default');
  });
});
