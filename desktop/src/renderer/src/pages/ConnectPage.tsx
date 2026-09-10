import { useEffect, useState } from 'react';
import type { ConnectionState } from '../../../shared/connectionState';
import type { UserProfile } from '../types';
import { t } from '../i18n';

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

  useEffect(() => {
    window.vpnApi.getConnectionState().then(setState);
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);

    const offState = window.vpnApi.onStateChange(setState);
    const offRegion = window.vpnApi.onRegionChange(setRegion);
    return () => {
      offState();
      offRegion();
    };
  }, []);

  const isActive = state === 'CONNECTED' || state === 'CONNECTING' || state === 'RECONNECTING';

  const onToggle = () => {
    if (isActive) {
      void window.vpnApi.disconnect();
    } else {
      void window.vpnApi.connect();
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
            <p className="mt-2 text-xs text-white/50">{sub.expiresAt}</p>
          </>
        ) : (
          <p className="text-sm text-white/60">{t.noSubscription}</p>
        )}
      </div>
    </div>
  );
}
