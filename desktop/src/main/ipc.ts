import { ipcMain, type BrowserWindow } from 'electron';
import type { ApiClient } from './api/apiClient';
import type { VpnController } from './vpn/vpnController';

/** All main<->renderer channels in one place; preload/index.ts exposes a matching typed surface. */
export function registerIpcHandlers(win: BrowserWindow, apiClient: ApiClient, vpn: VpnController): void {
  ipcMain.handle('auth:login', (_e, email: string, password: string) => apiClient.login(email, password));
  ipcMain.handle('auth:register', (_e, email: string, password: string, referralCode?: string) =>
    apiClient.register(email, password, referralCode)
  );
  ipcMain.handle('auth:logout', () => {
    void vpn.disconnect();
    apiClient.logout();
  });

  ipcMain.handle('profile:get', () => apiClient.getProfile());

  ipcMain.handle('regions:list', () => apiClient.getRegions());
  ipcMain.handle('region:get', () => apiClient.getSelectedRegion());
  ipcMain.handle('region:set', (_e, region: string | null) => apiClient.setSelectedRegion(region));

  ipcMain.handle('devices:list', () => apiClient.getDevices());
  ipcMain.handle('devices:delete', (_e, deviceId: number) => apiClient.deleteDevice(deviceId));

  ipcMain.handle('vpn:connect', () => vpn.connect());
  ipcMain.handle('vpn:disconnect', () => vpn.disconnect());
  ipcMain.handle('vpn:getState', () => vpn.getState());

  vpn.on('state', (state) => {
    if (!win.isDestroyed()) win.webContents.send('vpn:state', state);
  });
  vpn.on('region', (region) => {
    if (!win.isDestroyed()) win.webContents.send('vpn:region', region);
  });
  vpn.on('regionFallback', (fellBack) => {
    if (!win.isDestroyed()) win.webContents.send('vpn:regionFallback', fellBack);
  });
}
