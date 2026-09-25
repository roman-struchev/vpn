import { describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  app: { getLocale: () => 'ru-RU', getAppPath: () => '', isPackaged: false },
  Menu: {},
  nativeImage: {},
  Notification: {},
  Tray: vi.fn(),
}));
vi.mock('../src/main/p2p/relayAgent', () => ({ RelayAgent: vi.fn() }));
vi.mock('../src/main/geoLocale', () => ({ detectNodeRegion: vi.fn() }));

import { p2pMenuItems, p2pStatusLine } from '../src/main/tray';

const HOUR = 60 * 60 * 1000;
const view = (over: Partial<Parameters<typeof p2pStatusLine>[0]> = {}) => ({
  mode: 'OFF' as const,
  expiresAtEpochMs: null,
  durationMs: null,
  unsupportedNetwork: null,
  ...over,
});

describe('tray P2P section', () => {
  it('says what the relay is doing', () => {
    expect(p2pStatusLine(view())).toBe('P2P-раздача: выключена');
    expect(p2pStatusLine(view({ mode: 'ALWAYS' }))).toBe('P2P-раздача: включена всегда');
    expect(p2pStatusLine(view({ mode: 'TIMED', expiresAtEpochMs: Date.now() + HOUR, durationMs: HOUR }))).toMatch(
      /^P2P-раздача: включена до \d/
    );
    expect(p2pStatusLine(view({ unsupportedNetwork: 'SYMMETRIC' }))).toBe('P2P-раздача: не поддерживается в этой сети');
  });

  it('offers the same four choices as the app, the current one checked', () => {
    const setMode = vi.fn();
    const items = p2pMenuItems(view({ mode: 'TIMED', expiresAtEpochMs: Date.now() + 8 * HOUR, durationMs: 8 * HOUR }), true, {
      setMode,
      openApp: vi.fn(),
    });
    expect(items.map((i) => i.label)).toEqual([
      expect.stringMatching(/^P2P-раздача: включена до/),
      'Выключить',
      'На 1 час',
      'На 8 часов',
      'Всегда',
    ]);
    expect(items.filter((i) => i.checked).map((i) => i.label)).toEqual(['На 8 часов']);

    items[2].click!({} as never, undefined, {} as never);
    expect(setMode).toHaveBeenCalledWith('TIMED', HOUR);
    items[1].click!({} as never, undefined, {} as never);
    expect(setMode).toHaveBeenCalledWith('OFF');
  });

  it('sends to the app when this account cannot relay from here yet', () => {
    const openApp = vi.fn();
    const items = p2pMenuItems(view(), false, { setMode: vi.fn(), openApp });
    expect(items).toHaveLength(2);
    items[1].click!({} as never, undefined, {} as never);
    expect(openApp).toHaveBeenCalled();
  });
});
