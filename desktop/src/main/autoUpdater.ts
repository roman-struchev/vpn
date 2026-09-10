import { app } from 'electron';
import electronUpdater from 'electron-updater';

// electron-updater ships as CommonJS; its named exports aren't reliably
// synthesized under Node's ESM interop, so go through the default export.
const { autoUpdater } = electronUpdater;

/**
 * electron-updater + GitHub Releases, unsigned (docs/PLAN.md §5 "Desktop:
 * самообновление без сертификата" — the same electron-builder/
 * electron-updater/publish:github scheme as aurapad). Reads the
 * app-update.yml electron-builder generates at build time from
 * package.json's "build.publish" config — nothing else to configure here.
 */
export function initAutoUpdater(): void {
  if (!app.isPackaged) {
    console.log('Skipping auto-update check in dev (app is not packaged).');
    return;
  }

  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;

  autoUpdater.on('error', (err) => console.error('[autoUpdater]', err));
  autoUpdater.on('update-available', (info) => console.log('[autoUpdater] update available:', info.version));
  autoUpdater.on('update-downloaded', (info) =>
    console.log('[autoUpdater] update downloaded, will install on quit:', info.version)
  );

  autoUpdater.checkForUpdatesAndNotify().catch((err) => console.error('[autoUpdater] check failed', err));
}
