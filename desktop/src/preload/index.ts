import { contextBridge, ipcRenderer } from 'electron';
import type { ConnectionState } from '../shared/connectionState';

const vpnApi = {
  login: (email: string, password: string) => ipcRenderer.invoke('auth:login', email, password),
  register: (email: string, password: string, referralCode?: string) =>
    ipcRenderer.invoke('auth:register', email, password, referralCode),
  logout: () => ipcRenderer.invoke('auth:logout'),

  getProfile: () => ipcRenderer.invoke('profile:get'),

  listDevices: () => ipcRenderer.invoke('devices:list'),
  addDevice: (deviceName: string, platform: string) => ipcRenderer.invoke('devices:add', deviceName, platform),
  deleteDevice: (deviceId: number) => ipcRenderer.invoke('devices:delete', deviceId),

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
};

contextBridge.exposeInMainWorld('vpnApi', vpnApi);

export type VpnApi = typeof vpnApi;
