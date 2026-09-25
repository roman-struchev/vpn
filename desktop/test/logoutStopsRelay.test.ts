import { beforeEach, describe, expect, it, vi } from 'vitest';

// Logging out used to leave a running relay agent up: it kept relaying for the
// previous account (observed live: the Mac stayed an exit for the old account
// after "Выйти", until the app was quit).
const handlers = new Map<string, (...args: unknown[]) => unknown>();
vi.mock('electron', () => ({
  ipcMain: { handle: (channel: string, fn: (...args: unknown[]) => unknown) => handlers.set(channel, fn) },
  shell: { openExternal: vi.fn() },
  app: { setLoginItemSettings: vi.fn() },
}));
vi.mock('../src/main/auth/googleOAuth', () => ({ runGoogleLoginFlow: vi.fn() }));
vi.mock('../src/main/geoLocale', () => ({ isPublicIpRussian: vi.fn() }));
vi.mock('../src/main/autoUpdater', () => ({ applyUpdate: vi.fn(), currentUpdateNotice: vi.fn() }));

import { registerIpcHandlers } from '../src/main/ipc';

/** Any method not given explicitly is a no-op mock — registration touches many. */
function stub<T extends object>(given: T): T {
  return new Proxy(given, {
    get: (target, key) => (key in target ? (target as Record<string | symbol, unknown>)[key] : ((target as Record<string | symbol, unknown>)[key] = vi.fn())),
  });
}

describe('auth:logout', () => {
  beforeEach(() => handlers.clear());

  function setUp(setMode = vi.fn().mockResolvedValue(undefined)) {
    const order: string[] = [];
    const relayManager = stub({ setMode: vi.fn((...a: unknown[]) => { order.push('relay off'); return setMode(...a); }) });
    const apiClient = stub({ logout: vi.fn(async () => { order.push('logout'); }) });
    const vpn = stub({ disconnect: vi.fn().mockResolvedValue(undefined) });
    const win = stub({ webContents: stub({}) });
    registerIpcHandlers(win as never, apiClient as never, vpn as never, stub({}) as never, relayManager as never);
    return { relayManager, apiClient, order };
  }

  it('turns relay mode off before the session is gone', async () => {
    const { relayManager, order } = setUp();
    await handlers.get('auth:logout')!({});
    expect(relayManager.setMode).toHaveBeenCalledWith('OFF', null);
    expect(order).toEqual(['relay off', 'logout']);
  });

  it('still logs out when stopping the relay fails', async () => {
    const { apiClient } = setUp(vi.fn().mockRejectedValue(new Error('agent gone')));
    await handlers.get('auth:logout')!({});
    expect(apiClient.logout).toHaveBeenCalled();
  });
});
