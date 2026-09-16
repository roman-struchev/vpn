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
    expect(store.getP2pRelayMode()).toEqual({ mode: 'OFF', expiresAtEpochMs: null });
  });

  it('round-trips ALWAYS mode', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    store.saveP2pRelayMode('ALWAYS', null);
    expect(store.getP2pRelayMode()).toEqual({ mode: 'ALWAYS', expiresAtEpochMs: null });
  });

  it('round-trips TIMED mode with its expiry', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    const expiresAt = Date.now() + 3_600_000;
    store.saveP2pRelayMode('TIMED', expiresAt);
    expect(store.getP2pRelayMode()).toEqual({ mode: 'TIMED', expiresAtEpochMs: expiresAt });
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
    expect(store.getP2pRelayMode()).toEqual({ mode: 'OFF', expiresAtEpochMs: null });
  });

  it('defaults to null region before anything is saved', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    expect(store.getP2pRelayRegion()).toBeNull();
  });

  it('round-trips a manually-entered region, independent of selectedRegion', async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const store = new TokenStore();
    store.save('jwt-token', 42);
    store.saveSelectedRegion('Netherlands, Amsterdam'); // the VPN egress preference — a different fact entirely
    store.saveP2pRelayRegion('Russia, Moscow'); // this device's own physical location
    expect(store.getP2pRelayRegion()).toBe('Russia, Moscow');
    expect(store.getSelectedRegion()).toBe('Netherlands, Amsterdam');
  });
});
