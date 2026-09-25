import { contextBridge, ipcRenderer } from 'electron';
import type { FailureReason } from '../shared/failureReason';
import type { UpdateNotice, UpdateProgress } from '../shared/updatePlan';
import type { ConnectionState } from '../shared/connectionState';
import type { RussianRoutingMode } from '../shared/xrayConfigFactory';

const vpnApi = {
  platform: process.platform,
  login: (email: string, password: string) => ipcRenderer.invoke('auth:login', email, password),
  /** One-time code from the Telegram bot (/login) or the web dashboard. */
  loginWithCode: (code: string) => ipcRenderer.invoke('auth:loginWithCode', code),
  requestPasswordReset: (email: string): Promise<void> => ipcRenderer.invoke('auth:requestPasswordReset', email),
  confirmPasswordReset: (email: string, code: string, newPassword: string) =>
    ipcRenderer.invoke('auth:confirmPasswordReset', email, code, newPassword),
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
  getTariffs: () => ipcRenderer.invoke('tariffs:list'),

  getRegions: () => ipcRenderer.invoke('regions:list'),
  pingSelectedRegion: (): Promise<number | null> => ipcRenderer.invoke('regions:ping'),
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
  /** Why the last attempt ended in ERROR (null otherwise) — see shared/failureReason.ts. */
  getFailure: (): Promise<FailureReason | null> => ipcRenderer.invoke('vpn:getFailure'),
  onFailure: (callback: (reason: FailureReason | null) => void) => {
    const listener = (_e: unknown, reason: FailureReason | null) => callback(reason);
    ipcRenderer.on('vpn:failure', listener);
    return () => ipcRenderer.removeListener('vpn:failure', listener);
  },
  /** An app update the banner offers — see main/autoUpdater.ts. */
  getUpdateNotice: (): Promise<UpdateNotice | null> => ipcRenderer.invoke('update:get'),
  applyUpdate: (): Promise<void> => ipcRenderer.invoke('update:apply'),
  onUpdateAvailable: (callback: (notice: UpdateNotice) => void) => {
    const listener = (_e: unknown, notice: UpdateNotice) => callback(notice);
    ipcRenderer.on('update:available', listener);
    return () => {
      ipcRenderer.removeListener('update:available', listener);
    };
  },
  onUpdateProgress: (callback: (progress: UpdateProgress) => void) => {
    const listener = (_e: unknown, progress: UpdateProgress) => callback(progress);
    ipcRenderer.on('update:progress', listener);
    return () => {
      ipcRenderer.removeListener('update:progress', listener);
    };
  },
  /** The session ran out and could not be renewed: the user has to sign in again. */
  onSessionExpired: (callback: () => void) => {
    const listener = () => callback();
    ipcRenderer.on('session:expired', listener);
    return () => {
      ipcRenderer.removeListener('session:expired', listener);
    };
  },

  /**
   * P2P relay mode (docs/research/P2P_RELAY_FEASIBILITY.md §8) — opt-in,
   * authenticated-only, relays other users' encrypted VPN traffic for a
   * traffic credit on this account. acceptP2pTerms is required once before
   * setP2pRelayMode will do anything real (the server rejects a bootstrap-
   * token mint otherwise).
   */
  acceptP2pTerms: (): Promise<{ acceptedAt: string }> => ipcRenderer.invoke('p2p:acceptTerms'),
  getP2pStatus: (): Promise<{
    termsAccepted: boolean;
    isGuest: boolean;
    bytesCreditedToday: number;
    dailyCapBytes: number;
    remainingCapBytesToday: number;
  }> => ipcRenderer.invoke('p2p:getStatus'),
  getP2pRelayMode: (): Promise<{
    mode: 'OFF' | 'TIMED' | 'ALWAYS';
    expiresAtEpochMs: number | null;
    durationMs: number | null;
    region: string | null;
    unsupportedNetwork: 'SYMMETRIC' | 'NO_UDP' | null;
  }> => ipcRenderer.invoke('p2p:getMode'),
  setP2pRelayMode: (mode: 'OFF' | 'TIMED' | 'ALWAYS', expiresAtEpochMs: number | null, durationMs?: number): Promise<void> =>
    ipcRenderer.invoke('p2p:setMode', mode, expiresAtEpochMs, durationMs),
  getP2pTermsUrl: (): Promise<string> => ipcRenderer.invoke('p2p:getTermsUrl'),
  /** Fires whenever RelayManager's mode changes, including its own auto-off once a TIMED window expires (see relayManager.ts#scheduleExpiry). */
  onP2pModeChange: (
    callback: (mode: {
      mode: 'OFF' | 'TIMED' | 'ALWAYS';
      expiresAtEpochMs: number | null;
      durationMs: number | null;
      region: string | null;
      unsupportedNetwork: 'SYMMETRIC' | 'NO_UDP' | null;
    }) => void
  ) => {
    const listener = (
      _e: unknown,
      mode: {
        mode: 'OFF' | 'TIMED' | 'ALWAYS';
        expiresAtEpochMs: number | null;
        durationMs: number | null;
        region: string | null;
        unsupportedNetwork: 'SYMMETRIC' | 'NO_UDP' | null;
      }
    ) => callback(mode);
    ipcRenderer.on('p2p:mode', listener);
    return () => ipcRenderer.removeListener('p2p:mode', listener);
  },
};

contextBridge.exposeInMainWorld('vpnApi', vpnApi);

export type VpnApi = typeof vpnApi;
