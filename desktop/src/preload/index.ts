import { contextBridge, ipcRenderer } from 'electron';
import type { ConnectionState } from '../shared/connectionState';
import type { RussianRoutingMode } from '../shared/xrayConfigFactory';

const vpnApi = {
  platform: process.platform,
  login: (email: string, password: string) => ipcRenderer.invoke('auth:login', email, password),
  register: (email: string, password: string, referralCode?: string) =>
    ipcRenderer.invoke('auth:register', email, password, referralCode),
  deviceLogin: (referralCode?: string) => ipcRenderer.invoke('auth:deviceLogin', referralCode),
  /** Upgrades the current guest/device-trial account in place — see apiClient.ts#upgradeGuest. */
  upgradeGuest: (email: string, password: string) => ipcRenderer.invoke('auth:upgradeGuest', email, password),
  /**
   * Runs the entire Google OAuth "installed app" loopback flow in the main
   * process (opens the system browser, listens on 127.0.0.1 for the
   * redirect, exchanges the code for an ID token, then logs in against the
   * server) and resolves with the same AuthResponse shape as login/register.
   */
  googleLogin: (referralCode?: string) => ipcRenderer.invoke('auth:googleLogin', referralCode),
  logout: () => ipcRenderer.invoke('auth:logout'),

  getProfile: () => ipcRenderer.invoke('profile:get'),

  getRegions: () => ipcRenderer.invoke('regions:list'),
  pingRegions: (): Promise<Record<string, number>> => ipcRenderer.invoke('regions:ping'),
  getSelectedRegion: (): Promise<string | null> => ipcRenderer.invoke('region:get'),
  setSelectedRegion: (region: string | null) => ipcRenderer.invoke('region:set', region),

  /**
   * 'off': no RU-specific routing. 'bypassRu': RU sites go direct, everything
   * else through the tunnel (for a user physically in Russia). 'onlyRu': the
   * reverse — RU sites go through the tunnel (ideally via a Russia-located
   * node), everything else direct (for a Russian speaker outside Russia who
   * needs RU-geo-restricted sites specifically).
   */
  getRussianRoutingMode: (): Promise<RussianRoutingMode> => ipcRenderer.invoke('vpn:getRussianRoutingMode'),
  setRussianRoutingMode: (mode: RussianRoutingMode): Promise<void> =>
    ipcRenderer.invoke('vpn:setRussianRoutingMode', mode),
  /** True if this install's public IP was Russian the first time it was ever checked, pre-VPN — see main/geoLocale.ts. */
  getOriginalIpIsRussia: (): Promise<boolean> => ipcRenderer.invoke('locale:originalIpIsRussia'),

  listDevices: () => ipcRenderer.invoke('devices:list'),
  deleteDevice: (deviceId: number) => ipcRenderer.invoke('devices:delete', deviceId),

  /** Opens a URL in the system's default browser (e.g. the web dashboard). */
  openExternal: (url: string) => ipcRenderer.invoke('shell:openExternal', url),

  /**
   * Seamless client->web SSO handoff: mints a short-lived exchange code
   * server-side and opens `<web dashboard>?handoff_code=...&next=<next>` in
   * the system browser, so the user arrives already signed in instead of
   * hitting the web app's login page. `next` is a same-origin relative path
   * on the web app (e.g. '/').
   */
  openWebHandoff: (next: string) => ipcRenderer.invoke('auth:openWebHandoff', next),

  connect: () => ipcRenderer.invoke('vpn:connect'),
  disconnect: () => ipcRenderer.invoke('vpn:disconnect'),
  getConnectionState: (): Promise<ConnectionState> => ipcRenderer.invoke('vpn:getState'),

  onStateChange: (callback: (state: ConnectionState) => void) => {
    const listener = (_e: unknown, state: ConnectionState) => callback(state);
    ipcRenderer.on('vpn:state', listener);
    return () => ipcRenderer.removeListener('vpn:state', listener);
  },
  onRegionChange: (callback: (region: string | null) => void) => {
    const listener = (_e: unknown, region: string | null) => callback(region);
    ipcRenderer.on('vpn:region', listener);
    return () => ipcRenderer.removeListener('vpn:region', listener);
  },
  onRegionFallback: (callback: (fellBack: boolean) => void) => {
    const listener = (_e: unknown, fellBack: boolean) => callback(fellBack);
    ipcRenderer.on('vpn:regionFallback', listener);
    return () => ipcRenderer.removeListener('vpn:regionFallback', listener);
  },
};

contextBridge.exposeInMainWorld('vpnApi', vpnApi);

export type VpnApi = typeof vpnApi;
