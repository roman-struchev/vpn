import { app } from 'electron';
import os from 'node:os';
import type { ApiClient } from '../api/apiClient';
import type { TokenStore } from '../api/tokenStore';
import { RelayAgent, type RelayMode } from './relayAgent';

/**
 * Owns the RelayAgent's lifecycle from the UI/IPC side: minting a fresh p2p
 * bootstrap token, starting/stopping the agent, persisting the chosen mode
 * (see TokenStore#saveP2pRelayMode) so it can resume after an app restart,
 * and registering/unregistering this app as a login item for ALWAYS mode so
 * it also resumes after a full device reboot (docs §8.5 — "если пользователь
 * включил на всегда... должно продолжать работать" after either restart).
 */
export class RelayManager {
  private agent: RelayAgent | null = null;

  constructor(
    private readonly apiClient: ApiClient,
    private readonly tokenStore: TokenStore
  ) {}

  /** Call once at app startup (after login is confirmed) to silently resume a previously-active relay mode. */
  async resumeIfNeeded(): Promise<void> {
    const { mode, expiresAtEpochMs } = this.tokenStore.getP2pRelayMode();
    if (mode === 'OFF') return;
    if (mode === 'TIMED' && (!expiresAtEpochMs || expiresAtEpochMs <= Date.now())) {
      // The locally-remembered window already lapsed while the app was
      // closed — don't silently re-arm it. The server would refuse to
      // dispatch signals to it anyway (Node#isEligibleForRelay), but
      // resuming a visibly-expired mode in the UI would be actively
      // misleading, not just harmless.
      this.tokenStore.saveP2pRelayMode('OFF', null);
      return;
    }
    await this.setMode(mode, expiresAtEpochMs);
  }

  async setMode(mode: RelayMode, expiresAtEpochMs: number | null): Promise<void> {
    this.tokenStore.saveP2pRelayMode(mode, expiresAtEpochMs);
    this.syncLoginItem(mode);

    if (mode === 'OFF') {
      await this.agent?.stop();
      this.agent = null;
      return;
    }

    if (this.agent) {
      this.agent.setRelayMode(mode, expiresAtEpochMs);
      return;
    }

    const { token } = await this.apiClient.createP2pBootstrapToken();
    this.agent = new RelayAgent(this.apiClient.getGrpcTarget(), os.hostname(), 'auto');
    this.agent.on('error', (err) => console.warn('[p2p relay]', err));
    this.agent.on('aclRejected', (sessionId: string, host: string) =>
      console.warn(`[p2p relay] session ${sessionId} rejected: destination ${host} is a blocked private/loopback address`)
    );
    await this.agent.start(token, mode, expiresAtEpochMs);
  }

  getMode(): { mode: RelayMode; expiresAtEpochMs: number | null } {
    return this.tokenStore.getP2pRelayMode();
  }

  async shutdown(): Promise<void> {
    await this.agent?.stop();
    this.agent = null;
  }

  /**
   * ALWAYS mode needs to survive a full device reboot, not just an app
   * restart within the same running OS session — app.setLoginItemSettings is
   * Electron's cross-platform (macOS/Windows; a no-op on most Linux desktop
   * environments, which have no single standard autostart API Electron
   * covers) hook for "start this app automatically on login". TIMED/OFF
   * don't need this: a lapsed TIMED window has nothing useful to resume, and
   * OFF has nothing to resume at all.
   */
  private syncLoginItem(mode: RelayMode): void {
    app.setLoginItemSettings({ openAtLogin: mode === 'ALWAYS' });
  }
}
