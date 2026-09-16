import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// TokenStore reaches into Electron's `app`/`safeStorage` — mocked here with
// a real temp-dir userData path and encryption disabled (matching the class's
// own "no OS keychain available" degraded-but-functional fallback) so this
// exercises the real file read/write path, not a fully stubbed one.
let userDataDir: string;
vi.mock('electron', () => ({
  app: { getPath: () => userDataDir },
  safeStorage: { isEncryptionAvailable: () => false },
}));

describe('TokenStore P2P relay mode persistence', () => {
  beforeEach(() => {
    userDataDir = mkdtempSync(path.join(os.tmpdir(), 'p2p-tokenstore-test-'));
  });

  afterEach(() => {
    rmSync(userDataDir, { recursive: true, force: true });
    vi.resetModules();
  });

  it('defaults to OFF with no expiry before anything is saved', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    expect(store.getP2pRelayMode()).toEqual({ mode: 'OFF', expiresAtEpochMs: null, durationMs: null });
  });

  it('round-trips ALWAYS mode', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    store.saveP2pRelayMode('ALWAYS', null);
    expect(store.getP2pRelayMode()).toEqual({ mode: 'ALWAYS', expiresAtEpochMs: null, durationMs: null });
  });

  it('round-trips TIMED mode with its expiry', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    const expiresAt = Date.now() + 3_600_000;
    store.saveP2pRelayMode('TIMED', expiresAt);
    expect(store.getP2pRelayMode()).toEqual({ mode: 'TIMED', expiresAtEpochMs: expiresAt, durationMs: null });
  });

  /**
   * Regression coverage for the bug the repo owner reported (both the 1h and
   * 8h buttons showing active at once): expiresAtEpochMs alone can't tell
   * which TIMED duration produced it, so the UI needs durationMs stored
   * alongside it — this is the persistence half of that fix (see
   * p2pModeButtons.ts#isModeButtonActive for the UI half).
   */
  it('round-trips TIMED mode with which specific duration produced it', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    const oneHourMs = 3_600_000;
    const expiresAt = Date.now() + oneHourMs;
    store.saveP2pRelayMode('TIMED', expiresAt, oneHourMs);
    expect(store.getP2pRelayMode()).toEqual({ mode: 'TIMED', expiresAtEpochMs: expiresAt, durationMs: oneHourMs });
  });

  it('switching from an 8h window to a 1h window overwrites the stored duration, not just the expiry', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    const eightHoursMs = 8 * 3_600_000;
    const oneHourMs = 3_600_000;
    store.saveP2pRelayMode('TIMED', Date.now() + eightHoursMs, eightHoursMs);
    const newExpiresAt = Date.now() + oneHourMs;
    store.saveP2pRelayMode('TIMED', newExpiresAt, oneHourMs);
    expect(store.getP2pRelayMode()).toEqual({ mode: 'TIMED', expiresAtEpochMs: newExpiresAt, durationMs: oneHourMs });
  });

  it('does not disturb an existing auth session when saving relay mode', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    store.save('jwt-token', 42);
    store.saveP2pRelayMode('ALWAYS', null);
    expect(store.getToken()).toBe('jwt-token');
    expect(store.getP2pRelayMode().mode).toBe('ALWAYS');
  });

  it('logout (clear) drops the relay mode along with the session', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    store.save('jwt-token', 42);
    store.saveP2pRelayMode('ALWAYS', null);
    store.clear();
    expect(store.getP2pRelayMode()).toEqual({ mode: 'OFF', expiresAtEpochMs: null, durationMs: null });
  });

  it('defaults to null region before anything is saved', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    expect(store.getP2pRelayRegion()).toBeNull();
  });

  it('round-trips the auto-detected relay-node region, independent of selectedRegion', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    store.save('jwt-token', 42);
    store.saveSelectedRegion('Netherlands, Amsterdam'); // the VPN egress preference — a different fact entirely
    store.saveP2pRelayRegion('Russia, Moscow'); // this device's own geo-IP-detected location (see geoLocale.ts#detectNodeRegion)
    expect(store.getP2pRelayRegion()).toBe('Russia, Moscow');
    expect(store.getSelectedRegion()).toBe('Netherlands, Amsterdam');
  });
});
