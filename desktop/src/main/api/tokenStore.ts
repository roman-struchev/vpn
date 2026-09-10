import { app, safeStorage } from 'electron';
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import path from 'node:path';

interface StoredAuth {
  token: string;
  userId: number;
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

  save(token: string, userId: number): void {
    const payload: StoredAuth = { token, userId };
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

  clear(): void {
    if (existsSync(this.filePath)) unlinkSync(this.filePath);
  }
}
