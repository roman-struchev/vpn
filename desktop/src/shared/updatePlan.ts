/**
 * The pure half of the in-app updater (main/autoUpdater.ts): which way an
 * update can be applied on this platform, and turning the install script's
 * output into progress. No Electron/Node APIs, so it is unit tested.
 */

/**
 * - install: electron-updater downloads it and installs on restart (Windows
 *   NSIS, Linux AppImage).
 * - script: macOS. electron-updater's own installer (Squirrel.Mac) refuses
 *   an unsigned app, so it only *finds* the update; scripts/install-mac.sh
 *   (bundled in the app) swaps the bundle and the app relaunches itself —
 *   the same scheme as aura-pad.
 * - manual: anything that can't be replaced in place (a .deb, or a macOS app
 *   still running from its disk image): point at the releases page.
 */
export type UpdateMode = 'install' | 'script' | 'manual';

export interface UpdateNotice {
  version: string;
  mode: UpdateMode;
  /** The last attempt to apply it failed; the banner offers a retry. */
  failed?: boolean;
}

export interface UpdateProgress {
  phase: 'download' | 'install';
  percent?: number;
}

export function updateModeFor(platform: string, opts: { appImage: boolean; bundlePath: string | null }): UpdateMode {
  if (platform === 'win32') return 'install';
  if (platform === 'linux') return opts.appImage ? 'install' : 'manual';
  if (platform === 'darwin') {
    // Launched straight from the mounted .dmg: there is nothing on disk the
    // script could replace.
    if (!opts.bundlePath || opts.bundlePath.startsWith('/Volumes/')) return 'manual';
    return 'script';
  }
  return 'manual';
}

/** "/Applications/Aura VPN.app/Contents/MacOS/Aura VPN" -> "/Applications/Aura VPN.app" */
export function macBundlePath(exePath: string): string | null {
  const match = exePath.match(/^(.*?\.app)\/Contents\/MacOS\//);
  return match ? match[1] : null;
}

/**
 * Folds one chunk of the install script's output into the current progress.
 * Its step lines ("[2/5] Downloading ...", "[3/5] Mounting ...", "[4/5]
 * Installing ...") set the phase; curl's --progress-bar meter, redrawn with
 * \r as "#####   42.7%", gives the download percentage. Returns null when
 * nothing changed (so no message is sent for it).
 */
export function foldInstallProgress(chunk: string, last: UpdateProgress | null): UpdateProgress | null {
  let phase = last?.phase;
  let percent = last?.percent;
  if (/Downloading /.test(chunk)) {
    phase = 'download';
    percent = 0;
  } else if (/(Mounting|Installing into|Clearing the quarantine)/.test(chunk)) {
    phase = 'install';
    percent = undefined;
  } else if (phase === 'download') {
    // A chunk can hold several redraws; only the last is current.
    const meter = chunk.match(/\d{1,3}(?:\.\d+)?(?=%)/g);
    if (meter) percent = Math.min(100, Math.round(Number(meter[meter.length - 1])));
  }
  if (!phase) return null;
  if (phase === last?.phase && percent === last?.percent) return null;
  return percent === undefined ? { phase } : { phase, percent };
}
