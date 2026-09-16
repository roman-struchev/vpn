import { app } from 'electron';
import { EventEmitter } from 'node:events';
import os from 'node:os';
import type { ApiClient } from '../api/apiClient';
import type { TokenStore } from '../api/tokenStore';
import { detectNodeRegion } from '../geoLocale';
import { RelayAgent, type RelayMode } from './relayAgent';

/**
 * Owns the RelayAgent's lifecycle from the UI/IPC side: minting a fresh p2p
 * bootstrap token, starting/stopping the agent, persisting the chosen mode
 * (see TokenStore#saveP2pRelayMode) so it can resume after an app restart,
 * registering/unregistering this app as a login item for ALWAYS mode so it
 * also resumes after a full device reboot (docs §8.5 — "если пользователь
 * включил на всегда... должно продолжать работать" after either restart),
 * and — for TIMED mode — auto-switching back to OFF once the window elapses
 * (emits 'modeChanged' so ipc.ts can push the new state to the renderer;
 * see setMode's own doc for why this can't just wait for the next restart).
 */
export class RelayManager extends EventEmitter {
  private agent: RelayAgent | null = null;
  private expiryTimer: NodeJS.Timeout | null = null;

  constructor(
    private readonly apiClient: ApiClient,
    private readonly tokenStore: TokenStore
  ) {
    super();
  }

  /** Call once at app startup (after login is confirmed) to silently resume a previously-active relay mode. */
  async resumeIfNeeded(): Promise<void> {
    const { mode, expiresAtEpochMs, durationMs } = this.tokenStore.getP2pRelayMode();
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
    await this.setMode(mode, expiresAtEpochMs, durationMs ?? undefined);
  }

  /**
   * @param durationMs Which specific TIMED option (e.g. 1h vs 8h) produced
   *   this expiresAtEpochMs — purely a UI-display fact (see TokenStore's
   *   p2pRelayDurationMs doc for why expiresAtEpochMs alone can't answer
   *   "which button is active"), never sent to the server or used for any
   *   actual enforcement decision here. Irrelevant/omit for OFF and ALWAYS.
   *
   * TIMED also arms a local timer (see scheduleExpiry) that calls this same
   * method with 'OFF' once expiresAtEpochMs passes. Without it, nothing ever
   * turned the app's own relay agent off once the window lapsed — the server
   * would just start refusing it new sessions (Node#isEligibleForRelay), but
   * the agent kept running, and the UI kept showing "На 1 час" as active,
   * for as long as the app stayed open (repo owner's report: "не выключился
   * сам через час" — resumeIfNeeded only ever caught an already-lapsed
   * window at the *next app start*, never while running).
   */
  async setMode(mode: RelayMode, expiresAtEpochMs: number | null, durationMs?: number): Promise<void> {
    this.clearExpiryTimer();
    this.tokenStore.saveP2pRelayMode(mode, expiresAtEpochMs, durationMs);
    this.syncLoginItem(mode);

    if (mode === 'OFF') {
      await this.agent?.stop();
      this.agent = null;
      this.emit('modeChanged', this.getMode());
      return;
    }

    if (mode === 'TIMED' && expiresAtEpochMs) {
      this.scheduleExpiry(expiresAtEpochMs);
    }

    if (this.agent) {
      this.agent.setRelayMode(mode, expiresAtEpochMs);
      this.emit('modeChanged', this.getMode());
      return;
    }

    // Detected once, the first time this device ever registers as a p2p
    // node, then persisted and reused on every later start/resume — exactly
    // like a regular VPS node's install-time geo-IP lookup is a one-shot
    // thing, never re-run on every restart (see detectNodeRegion's own doc
    // comment). A laptop that later moves to a different country keeps its
    // originally-detected label rather than silently relabeling itself.
    let region = this.tokenStore.getP2pRelayRegion();
    if (!region) {
      region = await detectNodeRegion();
      this.tokenStore.saveP2pRelayRegion(region);
    }

    const { token } = await this.apiClient.createP2pBootstrapToken();
    this.agent = new RelayAgent(this.apiClient.getGrpcTarget(), await this.buildNodeHostname(), region);
    this.agent.on('error', (err) => console.warn('[p2p relay]', err));
    this.agent.on('aclRejected', (sessionId: string, host: string) =>
      console.warn(`[p2p relay] session ${sessionId} rejected: destination ${host} is a blocked private/loopback address`)
    );
    await this.agent.start(token, mode, expiresAtEpochMs);
    this.emit('modeChanged', this.getMode());
  }

  /** Fires setMode('OFF', null) once expiresAtEpochMs passes; always cleared+rearmed from setMode so it never fires against a stale window. */
  private scheduleExpiry(expiresAtEpochMs: number): void {
    const delayMs = Math.max(0, expiresAtEpochMs - Date.now());
    this.expiryTimer = setTimeout(() => {
      this.expiryTimer = null;
      this.setMode('OFF', null).catch((err) => console.warn('[p2p relay] auto-off on expiry failed', err));
    }, delayMs);
  }

  private clearExpiryTimer(): void {
    if (this.expiryTimer) {
      clearTimeout(this.expiryTimer);
      this.expiryTimer = null;
    }
  }

  /**
   * "<account email> · <device name>" — os.hostname() alone (the previous
   * behavior) is frequently a generic factory-default name (e.g.
   * "MacBookPro") shared across many unrelated users' machines, useless for
   * telling p2p nodes apart in the admin panel. Falls back to a bare
   * os.hostname() if the profile fetch fails for some reason — still better
   * than blocking relay mode entirely over a display-label lookup.
   */
  private async buildNodeHostname(): Promise<string> {
    try {
      const profile = await this.apiClient.getProfile();
      return `${profile.email} · ${os.hostname()}`;
    } catch {
      return os.hostname();
    }
  }

  getMode(): { mode: RelayMode; expiresAtEpochMs: number | null; durationMs: number | null; region: string | null } {
    return { ...this.tokenStore.getP2pRelayMode(), region: this.tokenStore.getP2pRelayRegion() };
  }

  async shutdown(): Promise<void> {
    this.clearExpiryTimer();
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
