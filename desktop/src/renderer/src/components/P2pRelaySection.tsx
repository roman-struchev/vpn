import { useEffect, useState } from 'react';
import { t } from '../i18n';
import { isModeButtonActive, needsP2pConsent, type RelayMode } from './p2pModeButtons';

const HOUR_MS = 60 * 60 * 1000;

/**
 * Account-level P2P relay mode: consent + mode selector + today's credit
 * stats (docs/research/P2P_RELAY_FEASIBILITY.md §8.6). Hidden entirely for a
 * guest/device-trial profile (isGuest, from GET /status) — the feature
 * requires real accountability a no-signup trial account doesn't have.
 */
export default function P2pRelaySection() {
  const [status, setStatus] = useState<{
    termsAccepted: boolean;
    isGuest: boolean;
    bytesCreditedToday: number;
    dailyCapBytes: number;
  } | null>(null);
  const [mode, setModeState] = useState<{
    mode: RelayMode;
    expiresAtEpochMs: number | null;
    durationMs: number | null;
    region: string | null;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // The mode the user just picked but has not yet consented to — the one-time
  // question, asked where it matters instead of gating the whole section.
  const [pending, setPending] = useState<{ mode: RelayMode; durationMs?: number } | null>(null);
  // Computed from the server's own origin (web/src/App.tsx's #p2p-terms
  // hash route, phase 5) rather than hardcoded — see ApiClient#getWebOrigin.
  const [termsUrl, setTermsUrl] = useState<string | null>(null);

  const reload = () => {
    window.vpnApi.getP2pStatus().then(setStatus).catch(() => undefined);
    window.vpnApi.getP2pRelayMode().then(setModeState).catch(() => undefined);
  };

  useEffect(() => {
    reload();
    window.vpnApi.getP2pTermsUrl().then(setTermsUrl).catch(() => undefined);
    // Keeps the mode buttons/expiry text in sync with RelayManager's own
    // auto-off once a TIMED window elapses — without this listener, a TIMED
    // window that expired while this screen stayed open/mounted would only
    // ever be reflected after a manual reload() (repo owner's report: the
    // "На 1 час" mode visibly stayed selected long after the hour was up).
    const offModeChange = window.vpnApi.onP2pModeChange(setModeState);
    return () => {
      offModeChange();
    };
  }, []);

  if (!status || status.isGuest) {
    return status?.isGuest ? (
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
        <h2 className="text-xs font-semibold uppercase tracking-wider text-white/70 mb-2">{t.p2pTitle}</h2>
        <p className="text-[11px] text-white/45">{t.p2pGuestNotice}</p>
      </div>
    ) : null;
  }

  const applyMode = async (next: RelayMode, durationMs?: number, acceptFirst = false) => {
    setBusy(true);
    setError(null);
    try {
      if (acceptFirst) {
        await window.vpnApi.acceptP2pTerms();
      }
      const expiresAtEpochMs = durationMs ? Date.now() + durationMs : null;
      await window.vpnApi.setP2pRelayMode(next, expiresAtEpochMs, durationMs);
      setPending(null);
      reload();
    } catch (e) {
      setError(t.p2pError + (e instanceof Error ? `: ${e.message}` : ''));
    } finally {
      setBusy(false);
    }
  };

  const changeMode = (next: RelayMode, durationMs?: number) => {
    if (needsP2pConsent(next, status.termsAccepted)) {
      setPending({ mode: next, durationMs });
      return;
    }
    void applyMode(next, durationMs);
  };

  const modeButtons: { label: string; mode: RelayMode; durationMs?: number }[] = [
    { label: t.p2pModeOff, mode: 'OFF' },
    { label: t.p2pMode1h, mode: 'TIMED', durationMs: 1 * HOUR_MS },
    { label: t.p2pMode8h, mode: 'TIMED', durationMs: 8 * HOUR_MS },
    { label: t.p2pModeAlways, mode: 'ALWAYS' },
  ];

  const isActive = (btn: (typeof modeButtons)[number]) => isModeButtonActive(mode, btn);

  return (
    <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
      <h2 className="text-xs font-semibold uppercase tracking-wider text-white/70 mb-2">{t.p2pTitle}</h2>
      <p className="text-[11px] leading-relaxed text-white/45 mb-2">{t.p2pDesc}</p>
      <a
        href="#"
        onClick={(e) => {
          e.preventDefault();
          if (termsUrl) void window.vpnApi.openExternal(termsUrl);
        }}
        className="text-[11px] text-brand-400 hover:underline"
      >
        {t.p2pTermsLinkText}
      </a>

      <div className="mt-3 grid grid-cols-4 gap-1.5">
        {modeButtons.map((btn) => (
          <button
            key={btn.label}
            type="button"
            disabled={busy}
            onClick={() => changeMode(btn.mode, btn.durationMs)}
            className={`rounded-lg py-1.5 text-[10px] font-medium transition-colors disabled:opacity-40 ${
              isActive(btn)
                ? 'bg-brand-600 text-white'
                : 'bg-dark-800 text-white/70 hover:bg-dark-750 border border-dark-750'
            }`}
          >
            {btn.label}
          </button>
        ))}
      </div>

      {pending && (
        <div className="mt-2.5 rounded-xl border border-dark-750/50 bg-dark-800/80 px-3 py-2.5">
          <p className="text-[11px] leading-relaxed text-white/60">{t.p2pConsentQuestion}</p>
          <div className="mt-2 flex gap-1.5">
            <button
              type="button"
              disabled={busy}
              onClick={() => void applyMode(pending.mode, pending.durationMs, true)}
              className="flex-1 rounded-lg bg-brand-600 py-1.5 text-[10px] font-semibold text-white hover:bg-brand-700 disabled:opacity-40 transition-colors"
            >
              {t.p2pAcceptTerms}
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => setPending(null)}
              className="rounded-lg border border-dark-750 bg-dark-800 px-3 py-1.5 text-[10px] font-medium text-white/70 hover:bg-dark-750 disabled:opacity-40 transition-colors"
            >
              {t.p2pConsentCancel}
            </button>
          </div>
        </div>
      )}

      {status.termsAccepted && (
        <>
          {mode?.mode === 'TIMED' && mode.expiresAtEpochMs && (
            <p className="mt-2 text-[10px] text-white/40">
              {t.p2pExpiresAt}: {new Date(mode.expiresAtEpochMs).toLocaleString()}
            </p>
          )}

          {/* Only known once the node has actually registered at least once
              (RelayManager#setMode detects+persists it on first start,
              "как при старте ноды" per the repo owner — same one-shot
              geo-IP detection a regular VPS node does at install time, never
              re-run on every restart) — shown read-only, never editable. */}
          {mode?.region && (
            <p className="mt-2 text-[10px] text-white/40">
              {t.p2pRegionLabel}: {mode.region}
            </p>
          )}

          <p className="mt-2.5 rounded-xl bg-dark-800/80 border border-dark-750/50 px-3 py-2 text-xs text-brand-400 font-medium">
            {t.p2pStatusToday}: {(status.bytesCreditedToday / 1_073_741_824).toFixed(2)} {t.p2pStatusOf}{' '}
            {(status.dailyCapBytes / 1_073_741_824).toFixed(0)} GB
          </p>
        </>
      )}

      {error && <p className="mt-2 text-xs text-state-error">{error}</p>}
    </div>
  );
}
