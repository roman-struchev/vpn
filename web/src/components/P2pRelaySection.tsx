import React, { useEffect, useState } from 'react';
import { Radio, Check } from 'lucide-react';
import { Lang, translations } from '../i18n';
import { P2pRelayStatus } from '../types';
import { api } from '../api';

interface P2pRelaySectionProps {
  lang: Lang;
}

/**
 * Consent + read-only stats card for the P2P relay feature (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.6). Turning relay mode ON only happens in the
 * native desktop/Android clients (separate phases) — this card's job is just
 * the terms link + brief summary the repo owner explicitly asked for
 * ("показываем как минимум ссылку на них и краткую выжимку"), the consent
 * checkbox POST /accept-terms gates, and a glance at today's credit once
 * accepted.
 */
export const P2pRelaySection: React.FC<P2pRelaySectionProps> = ({ lang }) => {
  const t = translations[lang];
  const [status, setStatus] = useState<P2pRelayStatus | null>(null);
  const [checked, setChecked] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadStatus = async () => {
    try {
      setStatus(await api.getP2pStatus());
    } catch {
      // Non-fatal — the card still renders its static summary/terms link
      // even if the status fetch fails (e.g. transient network hiccup).
      setStatus(null);
    }
  };

  useEffect(() => {
    loadStatus();
  }, []);

  const handleAccept = async () => {
    setError(null);
    setAccepting(true);
    try {
      await api.acceptP2pTerms();
      await loadStatus();
    } catch (err: any) {
      setError(err.message || t.p2pAcceptError);
    } finally {
      setAccepting(false);
    }
  };

  const capGb = status ? status.dailyCapBytes / (1024 * 1024 * 1024) : 50;
  const todayGb = status ? status.bytesCreditedToday / (1024 * 1024 * 1024) : 0;

  return (
    <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
      <h3 className="font-bold text-base mb-2 flex items-center gap-2">
        <Radio className="w-4 h-4 text-brand-500" />
        <span>{t.p2pTitle}</span>
      </h3>
      <p className="text-xs text-slate-400 leading-relaxed mb-2">{t.p2pSummary}</p>
      <a href="#p2p-terms" className="text-xs text-brand-500 hover:underline">
        {t.p2pTermsLink}
      </a>

      {status?.isGuest ? (
        <p className="mt-4 text-[11px] text-slate-500">{t.p2pGuestBlocked}</p>
      ) : status?.termsAccepted ? (
        <div className="mt-4 pt-4 border-t border-dark-800/80">
          <div className="flex items-center gap-1.5 text-[11px] text-emerald-400 font-semibold mb-3">
            <Check className="w-3.5 h-3.5" />
            <span>{t.p2pAccepted}</span>
          </div>
          <div className="grid grid-cols-2 gap-2 text-center mb-2">
            <div className="p-2.5 rounded-xl bg-dark-900 border border-dark-800">
              <span className="block text-[10px] text-slate-500 uppercase tracking-wider font-semibold">
                {t.p2pStatsToday}
              </span>
              <span className="text-base font-bold text-brand-400 mt-0.5 block">{todayGb.toFixed(2)} GB</span>
            </div>
            <div className="p-2.5 rounded-xl bg-dark-900 border border-dark-800">
              <span className="block text-[10px] text-slate-500 uppercase tracking-wider font-semibold">
                {t.p2pCapHint.replace('{cap}', capGb.toFixed(0))}
              </span>
              <span className="text-base font-bold text-white mt-0.5 block">
                {Math.min(100, Math.round((todayGb / capGb) * 100))}%
              </span>
            </div>
          </div>
          <p className="text-[11px] text-slate-500">{t.p2pEnableHint}</p>
        </div>
      ) : (
        <div className="mt-4 pt-4 border-t border-dark-800/80">
          <label className="flex items-start gap-2 text-xs text-slate-300 mb-3 cursor-pointer">
            <input
              type="checkbox"
              checked={checked}
              onChange={(e) => setChecked(e.target.checked)}
              className="mt-0.5"
            />
            <span>{t.p2pAcceptCheckbox}</span>
          </label>
          {error && <p className="text-[11px] text-red-400 mb-2">{error}</p>}
          <button
            type="button"
            disabled={!checked || accepting}
            onClick={handleAccept}
            className="px-4 py-2 rounded-xl bg-brand-500 hover:bg-brand-600 disabled:opacity-40 disabled:cursor-not-allowed text-dark-950 font-bold text-xs transition-all"
          >
            {t.p2pAcceptBtn}
          </button>
        </div>
      )}
    </div>
  );
};
