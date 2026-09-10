import type { VpnApi } from '../../preload/index';

declare global {
  interface Window {
    vpnApi: VpnApi;
  }
}

export {};
