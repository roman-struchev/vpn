import { useEffect, useState } from 'react';
import type { UpdateNotice, UpdateProgress } from '../../../shared/updatePlan';
import { t } from '../i18n';

/**
 * Offers an app update the main process found (main/autoUpdater.ts). Shown
 * over every screen, sign-in included: an update matters most exactly when
 * something does not work.
 *
 * Before this the updater only logged to the console, so an update was
 * never visible — and on macOS it could not install anyway.
 */
export default function UpdateBanner() {
  const [notice, setNotice] = useState<UpdateNotice | null>(null);
  const [progress, setProgress] = useState<UpdateProgress | null>(null);
  const [dismissedVersion, setDismissedVersion] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    window.vpnApi.getUpdateNotice().then((n) => n && setNotice(n)).catch(() => undefined);
    const offAvailable = window.vpnApi.onUpdateAvailable((n) => {
      setNotice(n);
      if (n.failed) {
        setBusy(false);
        setProgress(null);
      }
    });
    const offProgress = window.vpnApi.onUpdateProgress(setProgress);
    return () => {
      offAvailable();
      offProgress();
    };
  }, []);

  if (!notice || (dismissedVersion === notice.version && !busy && !notice.failed)) return null;

  const title = t.updateAvailable.replace('{version}', notice.version);
  const action =
    notice.mode === 'install' ? t.updateInstallAction : notice.mode === 'manual' ? t.updateDownloadAction : t.updateAction;
  const hint = notice.failed
    ? t.updateFailed
    : notice.mode === 'install'
      ? t.updateHintInstall
      : notice.mode === 'manual'
        ? t.updateHintManual
        : t.updateHintScript;

  let status: string | null = null;
  if (busy && progress?.phase === 'download') {
    status =
      progress.percent !== undefined
        ? t.updateDownloading.replace('{percent}', String(progress.percent))
        : t.updateDownloadingNoPercent;
  } else if (busy && progress?.phase === 'install') {
    status = t.updateInstalling;
  } else if (busy) {
    status = t.updateDownloadingNoPercent;
  }

  const apply = () => {
    // The script path takes a while and ends in a relaunch; the others hand
    // off straight away (installer restart, or the browser).
    if (notice.mode === 'script') setBusy(true);
    void window.vpnApi.applyUpdate();
  };

  return (
    <div className="fixed inset-x-3 bottom-3 z-50 rounded-2xl border border-brand-500/30 bg-dark-900/95 p-3 shadow-xl backdrop-blur-md">
      <p className="text-xs font-semibold text-white">{title}</p>
      <p className={`mt-1 text-[11px] leading-snug ${notice.failed ? 'text-state-error' : 'text-white/60'}`}>
        {status ?? hint}
      </p>
      {busy && progress?.phase === 'download' && progress.percent !== undefined && (
        <div className="mt-2 h-1 w-full overflow-hidden rounded-full bg-dark-800">
          <div className="h-full bg-brand-500 transition-all" style={{ width: `${progress.percent}%` }} />
        </div>
      )}
      {!busy && (
        <div className="mt-2.5 flex justify-end gap-2">
          <button
            type="button"
            onClick={() => setDismissedVersion(notice.version)}
            className="rounded-lg px-3 py-1.5 text-xs text-white/60 hover:text-white"
          >
            {t.updateLater}
          </button>
          <button
            type="button"
            onClick={apply}
            className="rounded-lg bg-brand-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-700"
          >
            {action}
          </button>
        </div>
      )}
    </div>
  );
}
