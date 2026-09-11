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

export default function ConnectPage() {
  const [state, setState] = useState<ConnectionState>('DISCONNECTED');
  const [region, setRegion] = useState<string | null>(null);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [regions, setRegions] = useState<RegionInfo[]>([]);
  const [selectedRegion, setSelectedRegionState] = useState<string | null>(null);
  const [regionFallback, setRegionFallback] = useState(false);
  const [billingError, setBillingError] = useState<string | null>(null);

  useEffect(() => {
    window.vpnApi.getConnectionState().then(setState);
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
    window.vpnApi.getRegions().then(setRegions).catch(() => undefined);
    window.vpnApi.getSelectedRegion().then(setSelectedRegionState).catch(() => undefined);

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
    <div className="flex flex-col items-center gap-6 px-8 py-10">
      <p className={`text-lg font-semibold ${STATE_COLOR[state]}`}>{STATE_LABEL[state]}</p>
      {region && <p className="text-xs text-white/50">{t.nodeRegion}: {region}</p>}

      <div className="w-full rounded-xl bg-dark-900 p-4">
        <label htmlFor="region-picker" className="mb-2 block text-sm font-semibold">
          {t.regionPickerTitle}
        </label>
        <select
          id="region-picker"
          value={selectedRegion ?? ''}
          onChange={(e) => onRegionPicked(e.target.value)}
          className="w-full rounded-lg bg-dark-800 px-3 py-2 text-sm text-white"
        >
          <option value="">{t.regionAuto}</option>
          {regions.map((r) => (
            <option key={r.region} value={r.region}>
              {r.region} — {LOAD_LABEL[r.loadLevel]} ({r.nodeCount} {t.regionNodeCountSuffix})
            </option>
          ))}
        </select>
        {selectedRegionInfo && (
          <p className={`mt-2 text-xs ${LOAD_COLOR[selectedRegionInfo.loadLevel]}`}>{LOAD_LABEL[selectedRegionInfo.loadLevel]}</p>
        )}
        {regionFallback && <p className="mt-2 text-xs text-state-connecting">{t.regionUnavailableNotice}</p>}
      </div>

      <button
        onClick={onToggle}
        className={`flex h-32 w-32 items-center justify-center rounded-full text-sm font-semibold text-white shadow-lg transition-colors ${
          isActive ? 'bg-brand-600 hover:bg-brand-700' : 'bg-dark-800 hover:bg-dark-800/80'
        }`}
      >
        {isActive ? t.disconnect : t.connect}
      </button>

      {state === 'OPERATOR_BLOCKED' && (
        <div className="w-full rounded-xl bg-dark-900 p-4">
          <p className="mb-1 text-sm font-semibold">{t.operatorBlockedTitle}</p>
          <p className="text-xs text-white/70">{t.operatorBlockedBody}</p>
        </div>
      )}

      <div className="w-full rounded-xl bg-dark-900 p-4">
        {profile?.hasActiveSubscription && sub ? (
          <>
            <p className="text-sm">
              {usedGb.toFixed(2)} / {limitGb.toFixed(0)} GB
            </p>
            <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-dark-800">
              <div className="h-full bg-brand-500" style={{ width: `${percent}%` }} />
            </div>
            <p className="mt-2 text-xs text-white/50">
              {t.expiresAt}: {new Date(sub.expiresAt).toLocaleDateString()}{' '}
              {new Date(sub.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </p>
          </>
        ) : (
          <div className="flex flex-col items-start gap-2">
            <p className="text-sm text-white/60">{t.noSubscription}</p>
            <button
              onClick={() => void openBilling()}
              className="text-xs font-semibold text-brand-500 hover:underline"
            >
              {t.getPlan}
            </button>
            {billingError && <p className="text-xs text-state-error">{billingError}</p>}
          </div>
        )}
      </div>
    </div>
  );
}
