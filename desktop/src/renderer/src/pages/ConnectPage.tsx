import { useEffect, useState } from 'react';
import type { ConnectionState } from '../../../shared/connectionState';
import type { RegionInfo, UserProfile } from '../types';
import { t } from '../i18n';

const LOAD_LABEL: Record<RegionInfo['loadLevel'], string> = {
  LOW: t.regionLoadLow,
  MEDIUM: t.regionLoadMedium,
  HIGH: t.regionLoadHigh,
};

const LOAD_COLOR: Record<RegionInfo['loadLevel'], string> = {
  LOW: 'text-state-connected',
  MEDIUM: 'text-state-connecting',
  HIGH: 'text-state-error',
};

const STATE_LABEL: Record<ConnectionState, string> = {
  DISCONNECTED: t.stateDisconnected,
  CONNECTING: t.stateConnecting,
  CONNECTED: t.stateConnected,
  RECONNECTING: t.stateReconnecting,
  OPERATOR_BLOCKED: t.stateOperatorBlocked,
  ERROR: t.stateError,
};

const STATE_COLOR: Record<ConnectionState, string> = {
  DISCONNECTED: 'text-state-disconnected',
  CONNECTING: 'text-state-connecting',
  CONNECTED: 'text-state-connected',
  RECONNECTING: 'text-state-reconnecting',
  OPERATOR_BLOCKED: 'text-state-blocked',
  ERROR: 'text-state-error',
};

export default function ConnectPage({
  isGuest,
  onSignInOrRegister,
}: {
  isGuest: boolean;
  onSignInOrRegister?: () => void;
}) {
  const [state, setState] = useState<ConnectionState>('DISCONNECTED');
  const [region, setRegion] = useState<string | null>(null);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [regions, setRegions] = useState<RegionInfo[]>([]);
  const [selectedRegion, setSelectedRegionState] = useState<string | null>(null);
  const [regionFallback, setRegionFallback] = useState(false);
  const [billingError, setBillingError] = useState<string | null>(null);
  const [pings, setPings] = useState<Record<string, number>>({});
  const [bypassRu, setBypassRu] = useState<boolean>(true);

  useEffect(() => {
    window.vpnApi.getConnectionState().then(setState);
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
    window.vpnApi.getRegions().then(setRegions).catch(() => undefined);
    window.vpnApi.getSelectedRegion().then(setSelectedRegionState).catch(() => undefined);
    window.vpnApi.getBypassRussianTraffic().then(setBypassRu).catch(() => undefined);
    window.vpnApi.pingRegions().then(setPings).catch(() => undefined);

    const offState = window.vpnApi.onStateChange(setState);
    const offRegion = window.vpnApi.onRegionChange(setRegion);
    const offRegionFallback = window.vpnApi.onRegionFallback(setRegionFallback);
    return () => {
      offState();
      offRegion();
      offRegionFallback();
    };
  }, []);

  const onRegionPicked = (value: string) => {
    const next = value === '' ? null : value;
    setSelectedRegionState(next);
    void window.vpnApi.setSelectedRegion(next);
  };

  const isActive = state === 'CONNECTED' || state === 'CONNECTING' || state === 'RECONNECTING';

  const onToggle = () => {
    if (isActive) {
      void window.vpnApi.disconnect();
    } else {
      void window.vpnApi.connect();
    }
  };

  const selectedRegionInfo = selectedRegion ? regions.find((r) => r.region === selectedRegion) : undefined;

  // There's no purchase/top-up UI in this app at all — billing only exists
  // on the web dashboard. Uses the seamless client->web SSO handoff (see
  // WEB_HANDOFF_RESEARCH.md) so the user lands there already signed in
  // instead of hitting the web app's login page. The web app has no
  // `/billing`-addressable route yet, so `next` is just the root.
  const openBilling = async () => {
    setBillingError(null);
    try {
      await window.vpnApi.openWebHandoff('/');
    } catch (e) {
      setBillingError(e instanceof Error ? e.message : String(e));
    }
  };

  const sub = profile?.subscription;
  const usedGb = sub ? sub.trafficUsedBytes / 1024 ** 3 : 0;
  const limitGb = sub ? sub.trafficLimitBytes / 1024 ** 3 : 0;
  const percent = limitGb > 0 ? Math.min(100, (usedGb / limitGb) * 100) : 0;

  return (
    <div className="flex flex-col items-center justify-between gap-5 px-6 py-4 min-h-full">
      {/* Top Controls: Region Picker & Neat Russian Traffic Bypass */}
      <div className="w-full flex flex-col gap-2.5">
        <div className="w-full rounded-2xl bg-dark-900 border border-dark-800/80 p-3.5">
          <div className="flex items-center justify-between mb-1.5">
            <label htmlFor="region-picker" className="text-xs font-semibold text-white/80">
              {t.regionPickerTitle}
            </label>
            {selectedRegionInfo && pings[selectedRegionInfo.region] !== undefined && (
              <span className="font-mono text-xs text-state-connected">
                ⚡ {pings[selectedRegionInfo.region]} {t.pingMs}
              </span>
            )}
          </div>
          <select
            id="region-picker"
            value={selectedRegion ?? ''}
            onChange={(e) => onRegionPicked(e.target.value)}
            className="w-full rounded-xl bg-dark-800 border border-dark-750/70 px-3 py-2 text-sm text-white outline-none cursor-pointer hover:bg-dark-750 transition-colors"
          >
            <option value="">{t.regionAuto}</option>
            {regions.map((r) => {
              const ping = pings[r.region];
              const pingText = ping !== undefined ? ` · ${ping} ${t.pingMs}` : '';
              return (
                <option key={r.region} value={r.region}>
                  {r.region} — {LOAD_LABEL[r.loadLevel]} ({r.nodeCount} {t.regionNodeCountSuffix}){pingText}
                </option>
              );
            })}
          </select>
          {selectedRegionInfo && (
            <div className="mt-2 flex items-center justify-between text-xs">
              <span className={LOAD_COLOR[selectedRegionInfo.loadLevel]}>{LOAD_LABEL[selectedRegionInfo.loadLevel]}</span>
              <span className="text-white/40">{selectedRegionInfo.nodeCount} {t.regionNodeCountSuffix}</span>
            </div>
          )}
          {regionFallback && <p className="mt-2 text-xs text-state-connecting">{t.regionUnavailableNotice}</p>}
        </div>

        {/* Neat Russian Traffic Bypass Toggle */}
        <div className="w-full rounded-2xl bg-dark-900 border border-dark-800/80 px-4 py-2.5">
          <label className="flex items-center justify-between cursor-pointer select-none">
            <div className="pr-3">
              <span className="text-xs font-medium text-white/90 block">{t.bypassRuTitle}</span>
              <span className="text-[11px] text-white/45 block leading-tight">{t.bypassRuDesc}</span>
            </div>
            <div className="relative inline-flex items-center cursor-pointer shrink-0">
              <input
                type="checkbox"
                checked={bypassRu}
                onChange={(e) => {
                  const val = e.target.checked;
                  setBypassRu(val);
                  void window.vpnApi.setBypassRussianTraffic(val);
                }}
                className="sr-only peer"
              />
              <div className="w-9 h-5 bg-dark-800 border border-dark-750 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-brand-500 peer-checked:border-brand-500"></div>
            </div>
          </label>
        </div>
      </div>

      {/* Center Action: Status Indicator & Large Connect Button */}
      <div className="flex flex-col items-center gap-4 my-auto">
        <div className="flex flex-col items-center gap-1">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-dark-900 border border-dark-800/80">
            <span
              className={`h-2 w-2 rounded-full ${
                state === 'CONNECTED'
                  ? 'bg-brand-500 animate-pulse'
                  : state === 'CONNECTING' || state === 'RECONNECTING'
                    ? 'bg-amber-500 animate-ping'
                    : state === 'ERROR' || state === 'OPERATOR_BLOCKED'
                      ? 'bg-red-500'
                      : 'bg-white/30'
              }`}
            />
            <span className={`text-xs font-semibold tracking-wide ${STATE_COLOR[state]}`}>
              {STATE_LABEL[state]}
            </span>
          </div>
          {region && isActive && (
            <p className="text-xs text-white/50">
              {t.nodeRegion}: <span className="text-white/80 font-medium">{region}</span>
            </p>
          )}
        </div>

        <button
          onClick={onToggle}
          className={`flex h-36 w-36 flex-col items-center justify-center rounded-full transition-all duration-300 active:scale-95 ${
            state === 'CONNECTED'
              ? 'bg-brand-600 hover:bg-brand-500 text-white shadow-[0_0_40px_rgba(34,197,94,0.35)] ring-4 ring-brand-500/20'
              : state === 'CONNECTING' || state === 'RECONNECTING'
                ? 'bg-amber-600/20 border-2 border-amber-500 text-amber-300 animate-pulse'
                : 'bg-dark-900 hover:bg-dark-850 text-white border-2 border-dark-800 hover:border-dark-750 shadow-xl'
          }`}
        >
          <svg
            className={`h-9 w-9 mb-1.5 transition-transform duration-300 ${
              isActive ? 'scale-110 text-white' : 'text-white/70'
            }`}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M18.36 6.64a9 9 0 1 1-12.73 0" />
            <line x1="12" y1="2" x2="12" y2="12" />
          </svg>
          <span className="text-xs font-semibold tracking-wider uppercase">
            {isActive ? t.disconnect : t.connect}
          </span>
        </button>
      </div>

      {state === 'OPERATOR_BLOCKED' && (
        <div className="w-full rounded-2xl bg-dark-900 border border-state-blocked/30 p-4">
          <p className="mb-1 text-sm font-semibold text-state-blocked">{t.operatorBlockedTitle}</p>
          <p className="text-xs text-white/70">{t.operatorBlockedBody}</p>
        </div>
      )}

      {/* Bottom Section: Subscription or Guest Sign-in */}
      <div className="w-full flex flex-col gap-2 mt-auto">
        <div className="w-full rounded-2xl bg-dark-900 border border-dark-800/80 p-3.5">
          {profile?.hasActiveSubscription && sub ? (
            <>
              <div className="flex items-center justify-between text-xs text-white/80 mb-1.5">
                <span className="font-medium">{usedGb.toFixed(2)} / {limitGb.toFixed(0)} GB</span>
                <span className="text-white/50">{percent.toFixed(0)}%</span>
              </div>
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-dark-800">
                <div className="h-full bg-brand-500 rounded-full transition-all duration-300" style={{ width: `${percent}%` }} />
              </div>
              <p className="mt-2 text-[11px] text-white/50">
                {t.expiresAt}: {new Date(sub.expiresAt).toLocaleDateString()}{' '}
                {new Date(sub.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
              </p>
            </>
          ) : (
            <div className="flex items-center justify-between">
              <div>
                <p className="text-xs font-medium text-white/80">{t.noSubscription}</p>
                {billingError && <p className="text-xs text-state-error mt-0.5">{billingError}</p>}
              </div>
              {!isGuest && (
                <button
                  onClick={() => void openBilling()}
                  className="rounded-lg bg-brand-600/20 border border-brand-500/30 px-3 py-1.5 text-xs font-semibold text-brand-400 hover:bg-brand-600/30 transition-colors"
                >
                  {t.getPlan}
                </button>
              )}
            </div>
          )}
        </div>

        {isGuest && onSignInOrRegister && (
          <div className="w-full rounded-2xl bg-dark-900 border border-brand-500/30 p-3.5">
            <p className="text-xs font-semibold text-white">{t.guestProfileTitle}</p>
            <p className="mt-1 text-[11px] leading-relaxed text-white/60">{t.guestProfileDesc}</p>
            <button
              type="button"
              className="mt-2.5 w-full rounded-xl bg-brand-600 py-2 text-xs font-semibold text-white transition-colors hover:bg-brand-700"
              onClick={onSignInOrRegister}
            >
              {t.signInOrRegister}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
