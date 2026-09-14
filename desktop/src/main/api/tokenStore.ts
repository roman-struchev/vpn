import { app, safeStorage } from 'electron';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

interface StoredAuth {
  /** Absent until the first successful login/register/deviceLogin. */
  token?: string;
  userId?: number;
  /**
   * The server-assigned Device row for this install, used to auto-register/
   * touch on connect instead of a manual "add device" step — see
   * VpnController's connect() and TokenStore.getDeviceId's Android
   * equivalent for the full rationale.
   */
  deviceId?: number;
  /**
   * User's pinned connection region (e.g. "nl-ams"), or undefined for
   * "auto"/best-available — today's implicit behavior. Set via the
   * ConnectPage region picker, read by VpnController#connect.
   */
  selectedRegion?: string;
  /**
   * Stable per-install identifier generated on first run (see
   * getOrCreateDeviceUuid), independent of and surviving before any auth
   * token exists. Sent to POST /api/v1/auth/device so a fresh install can
   * start on the trial tariff without registration — see ApiClient#deviceLogin.
   */
  deviceUuid?: string;
  /**
   * Whether this install's public IP geolocated to Russia the first time it
   * was ever checked (before the VPN could have connected and changed the
   * apparent exit IP) — see main/geoLocale.ts. Undefined means "not checked
   * yet, or the one check we tried failed/timed out" (deliberately left
   * uncached on failure so a flaky first launch gets retried, unlike the
   * fields above which cache unconditionally).
   */
  originalIpIsRussia?: boolean;
}

/**
 * Persists the JWT from /api/v1/auth/* using Electron's safeStorage (backed
 * by the OS keychain/DPAPI/libsecret) instead of a plaintext file.
 */
export class TokenStore {
  private readonly filePath: string;

  constructor() {
    this.filePath = path.join(app.getPath('userData'), 'auth.enc');
  }

  save(token: string, userId: number, deviceId?: number): void {
    // Preserves an already-stored selectedRegion/deviceUuid across a fresh
    // login/register (both are per-install, not per-JWT — there's no reason
    // a re-login should silently reset the region back to "auto" or hand out
    // a new device UUID).
    const current = this.load();
    const payload: StoredAuth = {
      token,
      userId,
      deviceId,
      selectedRegion: current?.selectedRegion,
      deviceUuid: current?.deviceUuid,
    };
    this.writePayload(payload);
  }

  private writePayload(payload: StoredAuth): void {
    if (safeStorage.isEncryptionAvailable()) {
      writeFileSync(this.filePath, safeStorage.encryptString(JSON.stringify(payload)));
    } else {
      // No OS-level secret store available (rare, e.g. some minimal Linux
      // setups without a keyring). Degraded but functional: the JWT is
      // short-lived and re-issuable via login.
      writeFileSync(this.filePath, JSON.stringify(payload), 'utf-8');
    }
  }

  load(): StoredAuth | null {
    if (!existsSync(this.filePath)) return null;
    try {
      const raw = readFileSync(this.filePath);
      const json = safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(raw) : raw.toString('utf-8');
      return JSON.parse(json) as StoredAuth;
    } catch {
      return null;
    }
  }

  getToken(): string | null {
    return this.load()?.token ?? null;
  }

  getDeviceId(): number | null {
    return this.load()?.deviceId ?? null;
  }

  /** No-op if there's no token yet (nothing to attach a deviceId to). */
  saveDeviceId(deviceId: number): void {
    const current = this.load();
    if (!current?.token || current.userId === undefined) return;
    this.save(current.token, current.userId, deviceId);
  }

  getSelectedRegion(): string | null {
    return this.load()?.selectedRegion ?? null;
  }

  /** No-op if there's no token yet (nothing to attach a region preference to). */
  saveSelectedRegion(region: string | null): void {
    const current = this.load();
    if (!current) return;
    this.writePayload({ ...current, selectedRegion: region ?? undefined });
  }

  /**
   * Returns this install's stable device UUID, generating and persisting one
   * on first call. Works even before any login has happened — unlike the
   * rest of this store, which only writes once an auth token exists.
   */
  getOrCreateDeviceUuid(): string {
    const current = this.load();
    if (current?.deviceUuid) return current.deviceUuid;
    const deviceUuid = randomUUID();
    this.writePayload({ ...current, deviceUuid });
    return deviceUuid;
  }

  /** Undefined = not yet successfully checked — caller should run the geo-IP lookup. */
  getOriginalIpIsRussia(): boolean | undefined {
    return this.load()?.originalIpIsRussia;
  }

  /** Works even before any login, like getOrCreateDeviceUuid — this is a per-install fact, not per-session. */
  saveOriginalIpIsRussia(isRussia: boolean): void {
    const current = this.load();
    this.writePayload({ ...current, originalIpIsRussia: isRussia });
  }

  /**
   * Clears the session (token/userId/deviceId) but deliberately keeps
   * deviceUuid/selectedRegion — same per-install fields save() already
   * preserves across a re-login. Wiping deviceUuid here used to mean every
   * logout minted a brand new device-trial account (and a fresh 3-day
   * trial) on the next launch instead of just returning to LoginPage; the
   * server now also refuses to auto-login a deviceUuid that's since been
   * upgraded to a real account (see DeviceAuthService), so keeping it here
   * is safe rather than a silent password-less re-entry risk.
   */
  clear(): void {
    const current = this.load();
    this.writePayload({
      deviceUuid: current?.deviceUuid,
      selectedRegion: current?.selectedRegion,
      originalIpIsRussia: current?.originalIpIsRussia,
    });
  }
}
