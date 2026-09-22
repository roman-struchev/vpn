import { describe, expect, it, vi } from 'vitest';

// VpnController constructs an XrayProcess, which resolves paths via electron's app.
vi.mock('electron', () => ({ app: { getPath: () => '/tmp', isPackaged: false, getAppPath: () => '/tmp' } }));

/**
 * region:set / vpn:setRussianRoutingMode must reconnect an active tunnel —
 * both settings only take effect when the connect flow runs again, so without
 * this the UI showed the new region/mode while traffic kept using the old one.
 * And it must do so without going through disconnect(): that switched the
 * system proxy off in between, sending every app straight out for a moment.
 */
describe('VpnController#reconnectIfActive', () => {
  const build = async (state: string) => {
    const { VpnController } = await import('../src/main/vpn/vpnController');
    const systemProxy = { enable: vi.fn(), disable: vi.fn() };
    const controller = new VpnController({} as any, systemProxy as any);
    vi.spyOn(controller, 'getState').mockReturnValue(state as any);
    const disconnect = vi.spyOn(controller, 'disconnect').mockResolvedValue(undefined);
    const loadAndStart = vi.spyOn(controller as any, 'loadAndStart').mockResolvedValue(undefined);
    return { controller, disconnect, loadAndStart, systemProxy };
  };

  it.each(['CONNECTED', 'CONNECTING', 'RECONNECTING'])('re-runs the connect flow from %s, proxy left on', async (state) => {
    const { controller, disconnect, loadAndStart, systemProxy } = await build(state);
    await controller.reconnectIfActive();
    expect(loadAndStart).toHaveBeenCalledOnce();
    expect(disconnect).not.toHaveBeenCalled();
    expect(systemProxy.disable).not.toHaveBeenCalled();
  });

  it.each(['DISCONNECTED', 'ERROR', 'OPERATOR_BLOCKED'])('does nothing from %s', async (state) => {
    const { controller, disconnect, loadAndStart } = await build(state);
    await controller.reconnectIfActive();
    expect(disconnect).not.toHaveBeenCalled();
    expect(loadAndStart).not.toHaveBeenCalled();
  });
});
