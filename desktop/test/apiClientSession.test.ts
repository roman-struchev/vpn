import http from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jwtExpiringIn } from './session.test';

let userDataDir: string;
vi.mock('electron', () => ({
  app: { getPath: () => userDataDir, isPackaged: false },
  safeStorage: { isEncryptionAvailable: () => false },
}));

/**
 * The desktop half of "signed out by the clock": renew before expiry, sign a
 * guest straight back in on a 401, and end a registered user's session
 * visibly — never by quietly creating a trial account for them.
 */
describe('ApiClient session handling', () => {
  let server: http.Server;
  let baseUrl: string;
  let accepted: string | null;
  const calls: Record<string, number> = {};

  beforeEach(async () => {
    userDataDir = mkdtempSync(path.join(os.tmpdir(), 'apiclient-session-test-'));
    accepted = null;
    for (const k of Object.keys(calls)) delete calls[k];
    server = http.createServer((req, res) => {
      const key = `${req.method} ${req.url}`;
      calls[key] = (calls[key] ?? 0) + 1;
      const authed = req.headers.authorization === `Bearer ${accepted}`;
      const json = (code: number, body: unknown) => {
        res.writeHead(code, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(body));
      };
      if (req.url === '/api/v1/auth/device') {
        accepted = jwtExpiringIn(30 * 24 * 3600);
        return json(200, { token: accepted, userId: 1 });
      }
      if (req.url === '/api/v1/auth/refresh') {
        if (!authed) return json(401, { error: 'Unauthorized' });
        accepted = jwtExpiringIn(30 * 24 * 3600);
        return json(200, { token: accepted, userId: 1 });
      }
      if (req.url === '/api/v1/user/profile') {
        return authed ? json(200, { id: 1, isGuest: true, hasActiveSubscription: false }) : json(401, { error: 'Unauthorized' });
      }
      json(404, { error: 'not found' });
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()));
    baseUrl = `http://127.0.0.1:${(server.address() as { port: number }).port}/`;
  });

  afterEach(() => {
    server.close();
    rmSync(userDataDir, { recursive: true, force: true });
    vi.resetModules();
  });

  const build = async () => {
    const { TokenStore } = await import('../src/main/api/tokenStore');
    const { ApiClient } = await import('../src/main/api/apiClient');
    const store = new TokenStore();
    return { store, api: new ApiClient(store, [baseUrl]) };
  };

  it('signs an expired guest straight back in and retries', async () => {
    const { store, api } = await build();
    store.saveSession(jwtExpiringIn(-10), 1, true);
    const profile = await api.getProfile();
    expect(profile.id).toBe(1);
    expect(calls['POST /api/v1/auth/device']).toBe(1);
    expect(store.getToken()).toBe(accepted);
  });

  it("ends a registered user's expired session visibly, without a trial account", async () => {
    const { store, api } = await build();
    store.saveSession(jwtExpiringIn(-10), 1, false);
    const expired = vi.fn();
    api.onSessionExpired(expired);
    await expect(api.getProfile()).rejects.toThrow('SESSION_EXPIRED');
    expect(calls['POST /api/v1/auth/device']).toBeUndefined();
    expect(store.getToken()).toBeNull();
    expect(expired).toHaveBeenCalledOnce();
  });

  it('renews a token close to expiry before using it', async () => {
    const { store, api } = await build();
    accepted = jwtExpiringIn(2 * 24 * 3600);
    const old = accepted;
    store.saveSession(old, 1, false);
    await api.getProfile();
    expect(calls['POST /api/v1/auth/refresh']).toBe(1);
    expect(store.getToken()).not.toBe(old);
  });

  it('leaves a token with plenty of time alone', async () => {
    const { store, api } = await build();
    accepted = jwtExpiringIn(20 * 24 * 3600);
    store.saveSession(accepted, 1, false);
    await api.getProfile();
    expect(calls['POST /api/v1/auth/refresh']).toBeUndefined();
  });
});
