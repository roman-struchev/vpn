import { ipcMain, shell, type BrowserWindow } from 'electron';
import type { ApiClient } from './api/apiClient';
import { runGoogleLoginFlow } from './auth/googleOAuth';
import type { VpnController } from './vpn/vpnController';

/** All main<->renderer channels in one place; preload/index.ts exposes a matching typed surface. */
export function registerIpcHandlers(win: BrowserWindow, apiClient: ApiClient, vpn: VpnController): void {
  ipcMain.handle('auth:login', (_e, email: string, password: string) => apiClient.login(email, password));
  ipcMain.handle('auth:register', (_e, email: string, password: string, referralCode?: string) =>
    apiClient.register(email, password, referralCode)
  );
  // No-signup trial flow: the device UUID lives in TokenStore, generated on
  // first call, so the renderer never has to know or manage it.
  ipcMain.handle('auth:deviceLogin', (_e, referralCode?: string) =>
    apiClient.deviceLogin(apiClient.getOrCreateDeviceUuid(), referralCode)
  );
  ipcMain.handle('auth:upgradeGuest', (_e, email: string, password: string) =>
    apiClient.upgradeGuest(email, password)
  );
  ipcMain.handle('auth:googleLogin', async (_e, referralCode?: string) => {
    // Runs the full loopback flow (opens system browser, listens on
    // 127.0.0.1, exchanges the code for an ID token) then hands the ID
    // token to the server, same as login()/register() above.
    const idToken = await runGoogleLoginFlow();
    return apiClient.googleAuth(idToken, referralCode);
  });
  ipcMain.handle('auth:logout', async () => {
    void vpn.disconnect();
    await apiClient.logout();
  });

  ipcMain.handle('profile:get', () => apiClient.getProfile());

  ipcMain.handle('regions:list', () => apiClient.getRegions());
  ipcMain.handle('region:get', () => apiClient.getSelectedRegion());
  ipcMain.handle('region:set', (_e, region: string | null) => apiClient.setSelectedRegion(region));

  ipcMain.handle('devices:list', () => apiClient.getDevices());
  ipcMain.handle('devices:delete', (_e, deviceId: number) => apiClient.deleteDevice(deviceId));

  // Renderer has no access to Node/Electron APIs (contextIsolation), so
  // opening a link in the system browser — e.g. the web dashboard's billing
  // page — has to be proxied through the main process, same as the
  // setWindowOpenHandler in main/index.ts uses for in-app link clicks.
  ipcMain.handle('shell:openExternal', (_e, url: string) => shell.openExternal(url));

  // Seamless client->web SSO handoff (see WEB_HANDOFF_RESEARCH.md): mints a
  // short-lived exchange code via the existing authenticated ApiClient, then
  // opens the web dashboard with that code so the user lands there already
  // signed in instead of hitting its login page. Same shell.openExternal
  // pattern as the Google OAuth loopback flow (auth/googleOAuth.ts) and the
  // plain openExternal channel above — just building the URL first.
  ipcMain.handle('auth:openWebHandoff', async (_e, next: string) => {
    const { code, webUrl } = await apiClient.requestWebHandoff();
    const url = `${webUrl}?handoff_code=${encodeURIComponent(code)}&next=${encodeURIComponent(next)}`;
    await shell.openExternal(url);
  });

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
