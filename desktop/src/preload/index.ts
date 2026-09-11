import { contextBridge, ipcRenderer } from 'electron';
import type { ConnectionState } from '../shared/connectionState';

const vpnApi = {
  login: (email: string, password: string) => ipcRenderer.invoke('auth:login', email, password),
  register: (email: string, password: string, referralCode?: string) =>
    ipcRenderer.invoke('auth:register', email, password, referralCode),
  deviceLogin: (referralCode?: string) => ipcRenderer.invoke('auth:deviceLogin', referralCode),
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
  getSelectedRegion: (): Promise<string | null> => ipcRenderer.invoke('region:get'),
  setSelectedRegion: (region: string | null) => ipcRenderer.invoke('region:set', region),

  listDevices: () => ipcRenderer.invoke('devices:list'),
  deleteDevice: (deviceId: number) => ipcRenderer.invoke('devices:delete', deviceId),

  /** Opens a URL in the system's default browser (e.g. the web dashboard). */
  openExternal: (url: string) => ipcRenderer.invoke('shell:openExternal', url),

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
