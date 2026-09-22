import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let userDataDir: string;
vi.mock('electron', () => ({
  app: { getPath: () => userDataDir },
  safeStorage: { isEncryptionAvailable: () => false },
}));

describe('TokenStore session and per-install fields', () => {
  beforeEach(() => {
    userDataDir = mkdtempSync(path.join(os.tmpdir(), 'tokenstore-session-test-'));
  });
  afterEach(() => {
    rmSync(userDataDir, { recursive: true, force: true });
    vi.resetModules();
  });

  const store = async () => new (await import('../src/main/api/tokenStore')).TokenStore();

  it('keeps install facts across a new sign-in (the geo-IP answer used to be lost)', async () => {
    const s = await store();
    s.saveOriginalIpIsRussia(true);
    s.saveRussianRoutingMode('off');
    s.saveSession('t1', 1, true);
    s.saveSession('t2', 2, false);
    expect(s.getOriginalIpIsRussia()).toBe(true);
    expect(s.getRussianRoutingMode()).toBe('off');
    expect(s.isDeviceAccount()).toBe(false);
  });

  it('attaching a device keeps the P2P relay settings', async () => {
    const s = await store();
    s.saveSession('t', 1, false);
    s.saveP2pRelayMode('ALWAYS', null);
    s.saveDeviceId(42);
    expect(s.getDeviceId()).toBe(42);
    expect(s.getP2pRelayMode().mode).toBe('ALWAYS');
  });

  it('a renewed token keeps the rest of the session; a dead one goes alone', async () => {
    const s = await store();
    s.saveSession('old', 1, true);
    s.saveDeviceId(7);
    s.replaceToken('new');
    expect(s.getToken()).toBe('new');
    expect(s.getDeviceId()).toBe(7);
    expect(s.isDeviceAccount()).toBe(true);
    s.clearToken();
    expect(s.getToken()).toBeNull();
    expect(s.getDeviceId()).toBe(7);
  });

  it('remembers ping targets for a limited time', async () => {
    const s = await store();
    s.savePingTarget('Netherlands', '203.0.113.1', 443, 1000);
    expect(s.getPingTarget('Netherlands', 2000, 5000)).toEqual({ host: '203.0.113.1', port: 443 });
    expect(s.getPingTarget('Netherlands', 10_000, 5000)).toBeNull();
    expect(s.getPingTarget('Germany', 2000, 5000)).toBeNull();
  });
});
