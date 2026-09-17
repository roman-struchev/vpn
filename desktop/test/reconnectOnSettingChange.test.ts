import { describe, expect, it, vi } from 'vitest';

// VpnController constructs an XrayProcess, which resolves paths via electron's app.
vi.mock('electron', () => ({ app: { getPath: () => '/tmp', isPackaged: false, getAppPath: () => '/tmp' } }));

/**
 * region:set / vpn:setRussianRoutingMode must reconnect an active tunnel —
 * both settings only take effect when connect() runs again, so without this
 * the UI showed the new region/mode while traffic kept using the old one.
 */
describe('VpnController#reconnectIfActive', () => {
  const build = async (state: string) => {
    const { VpnController } = await import('../src/main/vpn/vpnController');
    const controller = new VpnController({} as any, {} as any);
    vi.spyOn(controller, 'getState').mockReturnValue(state as any);
    const disconnect = vi.spyOn(controller, 'disconnect').mockResolvedValue(undefined);
    const connect = vi.spyOn(controller, 'connect').mockResolvedValue(undefined);
    return { controller, disconnect, connect };
  };

  it.each(['CONNECTED', 'CONNECTING', 'RECONNECTING'])('reconnects from %s', async (state) => {
    const { controller, disconnect, connect } = await build(state);
    await controller.reconnectIfActive();
    expect(disconnect).toHaveBeenCalledOnce();
    expect(connect).toHaveBeenCalledOnce();
  });

  it.each(['DISCONNECTED', 'ERROR', 'OPERATOR_BLOCKED'])('does nothing from %s', async (state) => {
    const { controller, disconnect, connect } = await build(state);
    await controller.reconnectIfActive();
    expect(disconnect).not.toHaveBeenCalled();
    expect(connect).not.toHaveBeenCalled();
  });
});
