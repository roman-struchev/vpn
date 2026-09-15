import { ipcMain, shell, type BrowserWindow } from 'electron';
import { ApiError, type ApiClient } from './api/apiClient';
import type { TokenStore } from './api/tokenStore';
import { runGoogleLoginFlow } from './auth/googleOAuth';
import { isPublicIpRussian } from './geoLocale';
import type { RelayManager } from './p2p/relayManager';
import type { RelayMode } from './p2p/relayAgent';
import type { VpnController } from './vpn/vpnController';
import type { RussianRoutingMode } from '../shared/xrayConfigFactory';

// Electron's ipcMain.handle only ever forwards a rejected handler's `message`
// string to the renderer (not the class/prototype, not any custom properties
// like ApiError#httpCode) — so a network-level failure (server unreachable)
// and a real "invalid/expired session" response are otherwise indistinguishable
// on the renderer side. App.tsx's startup checkAuth() needs to tell them apart
// (an unreachable server should show "server unavailable", not silently drop
// straight to the login screen as if the session were merely invalid) — mark
// network failures with this prefix so it survives the trip.
export const NETWORK_ERROR_PREFIX = 'NETWORK_ERROR:';

function rethrowTagged(e: unknown): never {
  if (e instanceof ApiError) throw e; // a real server response — not a connectivity problem
  const message = e instanceof Error ? e.message : String(e);
  throw new Error(`${NETWORK_ERROR_PREFIX} ${message}`);
}

/** All main<->renderer channels in one place; preload/index.ts exposes a matching typed surface. */
export function registerIpcHandlers(
  win: BrowserWindow,
  apiClient: ApiClient,
  vpn: VpnController,
  tokenStore: TokenStore,
  relayManager: RelayManager
): void {
  ipcMain.handle('auth:login', (_e, email: string, password: string) => apiClient.login(email, password));
  ipcMain.handle('auth:register', (_e, email: string, password: string, referralCode?: string) =>
    apiClient.register(email, password, referralCode)
  );
  // No-signup trial flow: the device UUID lives in TokenStore, generated on
  // first call, so the renderer never has to know or manage it.
  ipcMain.handle('auth:deviceLogin', (_e, referralCode?: string) =>
    apiClient.deviceLogin(apiClient.getOrCreateDeviceUuid(), referralCode).catch(rethrowTagged)
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

  ipcMain.handle('profile:get', () => apiClient.getProfile().catch(rethrowTagged));

  ipcMain.handle('regions:list', () => apiClient.getRegions());
  ipcMain.handle('regions:ping', () => apiClient.pingRegions());
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
  ipcMain.handle('vpn:getRussianRoutingMode', () => vpn.getRussianRoutingMode());
  ipcMain.handle('vpn:setRussianRoutingMode', (_e, mode: RussianRoutingMode) => vpn.setRussianRoutingMode(mode));

  // One-shot, cached-forever check of whether this install's public IP was
  // originally (pre-VPN) Russian — see geoLocale.ts. Only the bypass-RU
  // toggle's visibility depends on this, so a slow/failed lookup just leaves
  // that one row hidden this run rather than blocking anything else.
  ipcMain.handle('locale:originalIpIsRussia', async () => {
    const cached = tokenStore.getOriginalIpIsRussia();
    if (cached !== undefined) return cached;
    const result = await isPublicIpRussian();
    if (result !== null) tokenStore.saveOriginalIpIsRussia(result);
    return result ?? false;
  });

  // P2P relay mode (docs/research/P2P_RELAY_FEASIBILITY.md §8) — earning
  // traffic credit by relaying other users' encrypted VPN traffic. See
  // RelayManager for the actual lifecycle/autostart logic this proxies to.
  ipcMain.handle('p2p:acceptTerms', () => apiClient.acceptP2pRelayTerms());
  ipcMain.handle('p2p:getStatus', () => apiClient.getP2pRelayStatus().catch(rethrowTagged));
  ipcMain.handle('p2p:getMode', () => relayManager.getMode());
  // "#p2p-terms" matches the web dashboard's own hash-routed path
  // (web/src/App.tsx) for the phase-5 terms page — computed from the
  // current server origin rather than hardcoded, since that page lives on
  // whichever host apiClient is actually talking to (production IP today,
  // a real domain later), not a fixed guess.
  ipcMain.handle('p2p:getTermsUrl', () => `${apiClient.getWebOrigin()}/#p2p-terms`);
  ipcMain.handle('p2p:setMode', (_e, mode: RelayMode, expiresAtEpochMs: number | null) =>
    relayManager.setMode(mode, expiresAtEpochMs)
  );

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
