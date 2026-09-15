import { useEffect, useState } from 'react';
import { t } from '../i18n';

type RelayMode = 'OFF' | 'TIMED' | 'ALWAYS';

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
  const [mode, setModeState] = useState<{ mode: RelayMode; expiresAtEpochMs: number | null } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
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
  }, []);

  if (!status || status.isGuest) {
    return status?.isGuest ? (
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
        <h2 className="text-xs font-semibold uppercase tracking-wider text-white/70 mb-2">{t.p2pTitle}</h2>
        <p className="text-[11px] text-white/45">{t.p2pGuestNotice}</p>
      </div>
    ) : null;
  }

  const acceptTerms = async () => {
    setBusy(true);
    setError(null);
    try {
      await window.vpnApi.acceptP2pTerms();
      reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const changeMode = async (next: RelayMode, durationMs?: number) => {
    setBusy(true);
    setError(null);
    try {
      const expiresAtEpochMs = durationMs ? Date.now() + durationMs : null;
      await window.vpnApi.setP2pRelayMode(next, expiresAtEpochMs);
      reload();
    } catch (e) {
      setError(t.p2pError + (e instanceof Error ? `: ${e.message}` : ''));
    } finally {
      setBusy(false);
    }
  };

  const modeButtons: { label: string; mode: RelayMode; durationMs?: number }[] = [
    { label: t.p2pModeOff, mode: 'OFF' },
    { label: t.p2pMode1h, mode: 'TIMED', durationMs: 1 * HOUR_MS },
    { label: t.p2pMode8h, mode: 'TIMED', durationMs: 8 * HOUR_MS },
    { label: t.p2pModeAlways, mode: 'ALWAYS' },
  ];

  // TIMED's two buttons (1h/8h) both just show as "active" once TIMED mode
  // is on — which specific duration was last picked isn't tracked separately,
  // only the resulting expiresAtEpochMs (shown below instead).
  const isActive = (btn: (typeof modeButtons)[number]) => mode?.mode === btn.mode;

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

      {!status.termsAccepted ? (
        <button
          type="button"
          disabled={busy}
          onClick={() => void acceptTerms()}
          className="mt-3 w-full rounded-xl bg-brand-600 py-2 text-xs font-semibold text-white hover:bg-brand-700 disabled:opacity-40 transition-colors"
        >
          {t.p2pAcceptTerms}
        </button>
      ) : (
        <>
          <div className="mt-3 grid grid-cols-4 gap-1.5">
            {modeButtons.map((btn) => (
              <button
                key={btn.label}
                type="button"
                disabled={busy}
                onClick={() => void changeMode(btn.mode, btn.durationMs)}
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

          {mode?.mode === 'TIMED' && mode.expiresAtEpochMs && (
            <p className="mt-2 text-[10px] text-white/40">
              {t.p2pExpiresAt}: {new Date(mode.expiresAtEpochMs).toLocaleString()}
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
