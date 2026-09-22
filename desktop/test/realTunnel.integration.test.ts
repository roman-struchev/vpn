import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

/**
 * The desktop connect lifecycle against a REAL node: the real VpnController,
 * the real bundled xray binary, the real server API — only the OS system
 * proxy is faked (a test must not rewire the machine's network settings).
 *
 * Opt-in: needs a server and a node reachable from this machine at
 * 127.0.0.1, which android/scripts/real-tunnel-e2e.sh's setup can provide
 * with PUBLIC_IP=127.0.0.1. Run with:
 *   REAL_NODE_API=http://localhost:8080/ npx vitest run test/realTunnel.integration.test.ts
 *
 * What it proves beyond the faked lifecycle test (vpnControllerLifecycle):
 * the probe-in inbound works in a real xray config, "Protected" really
 * carries traffic, a key revoked mid-session is noticed and recovered from,
 * and switching region keeps the system proxy on.
 */
const API = process.env.REAL_NODE_API;
const DESKTOP_DIR = path.resolve(__dirname, '..');
let userDataDir = '';

vi.mock('electron', () => ({
  app: {
    getPath: () => userDataDir,
    isPackaged: false,
    getAppPath: () => DESKTOP_DIR,
  },
  safeStorage: { isEncryptionAvailable: () => false },
}));
vi.mock('../src/main/diagnostics', () => ({ reportError: vi.fn() }));

async function waitFor(check: () => boolean, timeoutMs: number, what: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (check()) return;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`timed out after ${timeoutMs}ms: ${what}`);
}

describe.skipIf(!API)('desktop tunnel against a real node', () => {
  beforeAll(() => {
    userDataDir = mkdtempSync(path.join(os.tmpdir(), 'desktop-real-tunnel-'));
  });
  afterAll(() => {
    rmSync(userDataDir, { recursive: true, force: true });
  });

  it(
    'connects for real, notices a revoked key, recovers, and switches region without dropping the proxy',
    async () => {
      const { TokenStore } = await import('../src/main/api/tokenStore');
      const { ApiClient } = await import('../src/main/api/apiClient');
      const { VpnController } = await import('../src/main/vpn/vpnController');
      const { probeThroughHttpProxy } = await import('../src/main/vpn/tunnelProbe');
      const { HTTP_PORT } = await import('../src/shared/xrayConfigFactory');

      const store = new TokenStore();
      const api = new ApiClient(store, [API as string]);
      await api.deviceLogin(store.getOrCreateDeviceUuid());

      const proxy = { enable: vi.fn(async () => undefined), disable: vi.fn(async () => undefined) };
      const vpn = new VpnController(api, proxy as never);

      try {
        await vpn.connect();
        expect(vpn.getState()).toBe('CONNECTED');
        expect(proxy.enable).toHaveBeenCalledOnce();
        // Through the ordinary inbound an app would use, not just probe-in.
        expect(await probeThroughHttpProxy(HTTP_PORT)).toBe(true);

        // Revoked from outside, mid-session: xray keeps running locally.
        for (const device of await api.getDevices()) {
          await api.deleteDevice(device.id);
        }
        await waitFor(() => vpn.getState() !== 'CONNECTED', 100_000, 'a revoked key must not stay CONNECTED');
        await waitFor(() => vpn.getState() === 'CONNECTED', 300_000, 'recovery by re-registering the device');
        expect(await probeThroughHttpProxy(HTTP_PORT)).toBe(true);

        // A region/mode change: the proxy must stay on throughout.
        proxy.disable.mockClear();
        await vpn.reconnectIfActive();
        expect(vpn.getState()).toBe('CONNECTED');
        expect(proxy.disable).not.toHaveBeenCalled();
        expect(await probeThroughHttpProxy(HTTP_PORT)).toBe(true);
      } finally {
        await vpn.disconnect();
      }
      expect(vpn.getState()).toBe('DISCONNECTED');
      expect(proxy.disable).toHaveBeenCalled();
      expect(await probeThroughHttpProxy(HTTP_PORT, 3000)).toBe(false);
    },
    600_000
  );
});
