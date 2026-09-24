import { app, shell } from 'electron';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import electronUpdater from 'electron-updater';
import {
  foldInstallProgress,
  macBundlePath,
  updateModeFor,
  type UpdateNotice,
  type UpdateProgress,
} from '../shared/updatePlan';

// electron-updater ships as CommonJS; its named exports aren't reliably
// synthesized under Node's ESM interop, so go through the default export.
const { autoUpdater } = electronUpdater;

const RELEASES_URL = 'https://github.com/roman-struchev/vpn/releases/latest';

/** Checked on launch and then every few hours, so a tray-resident app still hears about releases. */
const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000;

export interface UpdaterHooks {
  /** Delivers a notice/progress to the window (the banner). */
  send(channel: 'update:available' | 'update:progress', payload: UpdateNotice | UpdateProgress): void;
  /**
   * The local HTTP proxy port while the VPN is up, else null. The script's
   * downloads go through it: GitHub is often the very thing that is blocked
   * for the people using this app.
   */
  tunnelProxyPort(): number | null;
}

let hooks: UpdaterHooks | null = null;
let updateDownloaded = false;
let lastNotice: UpdateNotice | null = null;
let lastProgress: UpdateProgress | null = null;
let applying = false;

/**
 * electron-updater + GitHub Releases, unsigned — the same scheme as aura-pad
 * (src/main/updater.ts there).
 *
 * It used to run with autoDownload/autoInstallOnAppQuit on every platform
 * and only log to the console. On macOS that could never work: Squirrel.Mac
 * refuses to install into an unsigned app and needs a .zip the release
 * doesn't publish — so the update downloaded, failed to install, and nobody
 * ever saw anything. Now macOS only *checks*, tells the user, and applies it
 * with the bundled install script on request.
 */
export function initAutoUpdater(h: UpdaterHooks): void {
  hooks = h;
  if (!app.isPackaged) {
    console.log('Skipping auto-update check in dev (app is not packaged).');
    return;
  }

  const mode = currentMode();
  autoUpdater.autoDownload = mode === 'install';
  // Even if the banner is ignored, a downloaded update goes in on the next quit.
  autoUpdater.autoInstallOnAppQuit = mode === 'install';

  autoUpdater.on('update-available', (info) => {
    if (mode === 'install') return; // announced once it is downloaded, below
    notify({ version: info.version, mode });
  });
  autoUpdater.on('update-downloaded', (info) => {
    updateDownloaded = true;
    notify({ version: info.version, mode: 'install' });
  });
  autoUpdater.on('error', (err) => console.warn('[autoUpdater]', err?.message ?? err));

  check();
  setInterval(check, CHECK_INTERVAL_MS);
}

/** What the window asks for when it (re)loads: the banner survives a reload. */
export function currentUpdateNotice(): UpdateNotice | null {
  return lastNotice;
}

/** The banner's button. */
export function applyUpdate(): void {
  if (updateDownloaded) {
    autoUpdater.quitAndInstall();
    return;
  }
  if (lastNotice?.mode === 'script') {
    runInstallScript();
    return;
  }
  void shell.openExternal(RELEASES_URL);
}

function currentMode() {
  return updateModeFor(process.platform, {
    appImage: Boolean(process.env.APPIMAGE),
    bundlePath: process.platform === 'darwin' ? macBundlePath(app.getPath('exe')) : null,
  });
}

function check(): void {
  // Best-effort: offline, GitHub blocked, rate-limited — try again next time.
  autoUpdater.checkForUpdates().catch((err) => console.warn('[autoUpdater] check failed', err?.message ?? err));
}

function notify(notice: UpdateNotice): void {
  // Each version is announced once — except a failure, which must reach the
  // banner even for the same version, or it would sit on its spinner.
  if (!notice.failed && lastNotice && !lastNotice.failed && lastNotice.version === notice.version) return;
  lastNotice = notice;
  hooks?.send('update:available', notice);
}

function runInstallScript(): void {
  if (applying) return;
  const bundle = macBundlePath(app.getPath('exe'));
  const script = path.join(process.resourcesPath, 'install-mac.sh');
  if (!bundle || !existsSync(script)) {
    void shell.openExternal(RELEASES_URL);
    return;
  }
  applying = true;
  lastProgress = null;

  const env: NodeJS.ProcessEnv = {
    ...process.env,
    AURA_VPN_MANAGED_RELAUNCH: '1',
    AURA_VPN_INSTALL_DIR: path.dirname(bundle),
  };
  const proxyPort = hooks?.tunnelProxyPort() ?? null;
  if (proxyPort) {
    env.https_proxy = `http://127.0.0.1:${proxyPort}`;
    env.HTTPS_PROXY = env.https_proxy;
  }

  const child = spawn('/bin/bash', [script], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  const onOutput = (chunk: string) => {
    const next = foldInstallProgress(chunk, lastProgress);
    if (!next) return;
    lastProgress = next;
    hooks?.send('update:progress', next);
  };
  child.stdout.on('data', onOutput);
  child.stderr.on('data', onOutput);
  child.once('error', failed);
  child.once('exit', (code) => {
    if (code !== 0) {
      failed();
      return;
    }
    // The bundle on disk is the new version now. Relaunch it and quit
    // through the normal path, which disconnects the VPN and switches the
    // system proxy off first (see before-quit in index.ts).
    app.relaunch();
    app.quit();
  });
}

function failed(): void {
  applying = false;
  if (lastNotice) notify({ ...lastNotice, failed: true });
}
