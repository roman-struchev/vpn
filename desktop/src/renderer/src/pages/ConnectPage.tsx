import { useEffect, useState } from 'react';
import type { ConnectionState } from '../../../shared/connectionState';
import type { RegionInfo, UserProfile } from '../types';
import type { RussianRoutingMode } from '../../../shared/xrayConfigFactory';
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
  const [russianMode, setRussianMode] = useState<RussianRoutingMode>('bypassRu');
  const [refreshingUsage, setRefreshingUsage] = useState(false);
  // The bypass-RU toggle only makes sense for someone actually in Russia —
  // shown if EITHER the OS/app locale is Russian OR this install's public IP
  // geolocated to Russia the first time it was ever checked, pre-VPN (see
  // main/geoLocale.ts). Locale is synchronous and known immediately; the IP
  // check is async and defaults to not-shown until it resolves rather than
  // flashing the toggle in and then hiding it.
  const isRussianLocale = navigator.language.toLowerCase().startsWith('ru');
  const [originalIpIsRussia, setOriginalIpIsRussia] = useState(false);
  const showBypassRuToggle = isRussianLocale || originalIpIsRussia;

  const refreshProfile = () => {
    setRefreshingUsage(true);
    window.vpnApi
      .getProfile()
      .then(setProfile)
      .catch(() => undefined)
      .finally(() => setRefreshingUsage(false));
  };

  useEffect(() => {
    window.vpnApi.getConnectionState().then(setState);
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
    window.vpnApi.getRegions().then(setRegions).catch(() => undefined);
    window.vpnApi
      .getSelectedRegion()
      .then((picked) => {
        setSelectedRegionState(picked);
        refreshSelectedPing(picked);
      })
      .catch(() => undefined);
    window.vpnApi.getRussianRoutingMode().then(setRussianMode).catch(() => undefined);
    // Skip the network round-trip entirely when locale already settles it.
    if (!isRussianLocale) {
      window.vpnApi.getOriginalIpIsRussia().then(setOriginalIpIsRussia).catch(() => undefined);
    }

    const offState = window.vpnApi.onStateChange(setState);
    const offRegion = window.vpnApi.onRegionChange(setRegion);
    const offRegionFallback = window.vpnApi.onRegionFallback(setRegionFallback);
    // Traffic usage only changes server-side while a session is active, and
    // the app has no push channel for it — poll at a modest cadence instead
    // of leaving the number stale until the app is relaunched. The manual
    // button below covers "I want it right now".
    const usageInterval = setInterval(() => {
      window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
    }, 60_000);
    return () => {
      offState();
      offRegion();
      offRegionFallback();
      clearInterval(usageInterval);
    };
  }, []);

  /**
   * Latency for the picked region only — never the whole list. Each
   * measurement opens a real TCP connection to a node's live inbound, so
   * measuring every region on every mount put a connection per region on the
   * fleet each time (and fed the same activeConnections the load indicator is
   * derived from). The list compares by load and node count; a latency number
   * is only acted on for the region actually in use.
   */
  const refreshSelectedPing = (picked: string | null) => {
    if (!picked) {
      setPings({}); // "Auto" — the server picks the node, so there is nothing stable to measure
      return;
    }
    window.vpnApi
      .pingSelectedRegion()
      .then((ms) => setPings(ms === null ? {} : { [picked]: ms }))
      .catch(() => undefined);
  };

  const onRegionPicked = (value: string) => {
    const next = value === '' ? null : value;
    setSelectedRegionState(next);
    // Persisted first: the main process reads the stored selection back when
    // it measures, so pinging before this resolves would measure the old one.
    void window.vpnApi.setSelectedRegion(next).then(() => refreshSelectedPing(next));
  };

  const isActive = state === 'CONNECTED' || state === 'CONNECTING' || state === 'RECONNECTING';
  // Disconnected/Connected are already obvious from the button itself (color +
  // "ПОДКЛЮЧИТЬ"/"ОТКЛЮЧИТЬ" label). Operator-blocked gets its own dedicated
  // card below instead. Only these three actually need a separate indicator.
  const showStatusBadge = state === 'CONNECTING' || state === 'RECONNECTING' || state === 'ERROR';

  const onToggle = () => {
    if (isActive) {
      void window.vpnApi.disconnect();
    } else {
      void window.vpnApi.connect();
    }
  };

  const selectedRegionInfo = selectedRegion ? regions.find((r) => r.region === selectedRegion) : undefined;
  // 'onlyRu' mode only actually reaches RU-geo-restricted sites through a
  // Russia-located exit node — surfaced here (from data already fetched for
  // the region picker) rather than letting the mode look selected while
  // silently doing nothing useful.
  const hasAccessibleRussianRegion = regions.some((r) => r.accessible && /russia/i.test(r.region));

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
      {/* Top Controls: Region Picker */}
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
              // Locked regions stay listed (so a trial user can see what a
              // higher plan unlocks) but aren't selectable — picking one used
              // to silently reconnect elsewhere with a vague "unavailable"
              // message; disabling the option here closes that off at the
              // source instead of explaining it after the fact.
              return (
                <option key={r.region} value={r.region} disabled={!r.accessible}>
                  {r.accessible
                    ? `${r.region} — ${LOAD_LABEL[r.loadLevel]} (${r.nodeCount} ${t.regionNodeCountSuffix})${pingText}`
                    : `🔒 ${r.region} — ${t.regionLockedSuffix}`}
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
          {regionFallback && (
            selectedRegionInfo && !selectedRegionInfo.accessible ? (
              <div className="mt-2 flex items-center justify-between gap-2 text-xs text-state-connecting">
                <span>{t.regionRequiresUpgradeNotice}</span>
                {!isGuest && (
                  <button
                    type="button"
                    onClick={() => void openBilling()}
                    className="shrink-0 font-semibold underline decoration-dotted underline-offset-2 hover:text-state-connected"
                  >
                    {t.getPlan}
                  </button>
                )}
              </div>
            ) : (
              <p className="mt-2 text-xs text-state-connecting">{t.regionUnavailableNotice}</p>
            )
          )}
        </div>
      </div>

      {/* Center Action: Status Indicator & Large Connect Button */}
      <div className="flex flex-col items-center gap-4 my-auto">
        {(showStatusBadge || (region && isActive)) && (
          <div className="flex flex-col items-center gap-1">
            {/* Connected/disconnected are already unambiguous from the button's own
                color + label below — this badge only adds value for the states the
                button can't otherwise distinguish (connecting vs. reconnecting look
                identical on the button, and error looks identical to idle). */}
            {showStatusBadge && (
              <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-dark-900 border border-dark-800/80">
                <span
                  className={`h-2 w-2 rounded-full ${
                    state === 'ERROR' ? 'bg-red-500' : 'bg-amber-500 animate-ping'
                  }`}
                />
                <span className={`text-xs font-semibold tracking-wide ${STATE_COLOR[state]}`}>
                  {STATE_LABEL[state]}
                </span>
              </div>
            )}
            {region && isActive && (
              <p className="text-xs text-white/50">
                {t.nodeRegion}: <span className="text-white/80 font-medium">{region}</span>
              </p>
            )}
          </div>
        )}

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

      {/* Compact Russian Routing Mode control — only relevant to users actually in Russia
          or Russian speakers abroad (see showBypassRuToggle above) */}
      {showBypassRuToggle && (
        <div className="w-full rounded-xl bg-dark-900 border border-dark-800/80 px-3 py-1.5">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[11px] font-medium text-white/70">{t.russianModeTitle}</span>
            <div className="flex rounded-lg bg-dark-800 border border-dark-750/70 p-0.5 text-[10px] font-semibold">
              {(
                [
                  ['off', t.russianModeOff],
                  ['bypassRu', t.russianModeBypass],
                  ['onlyRu', t.russianModeOnlyRu],
                ] as [RussianRoutingMode, string][]
              ).map(([mode, label]) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => {
                    setRussianMode(mode);
                    void window.vpnApi.setRussianRoutingMode(mode);
                  }}
                  className={`px-2 py-1 rounded-md transition-colors ${
                    russianMode === mode ? 'bg-brand-600 text-white' : 'text-white/50 hover:text-white/80'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
          {/* Short labels alone ("RU direct" / "RU only") don't say which
              direction traffic goes — always spell out the current mode's
              actual behavior instead of relying on the pill text alone. */}
          <p className="mt-1.5 text-[10px] text-white/45 leading-snug">
            {russianMode === 'off' && t.russianModeOffDesc}
            {russianMode === 'bypassRu' && t.russianModeBypassDesc}
            {russianMode === 'onlyRu' && t.russianModeOnlyRuDesc}
          </p>
          {russianMode === 'onlyRu' && !hasAccessibleRussianRegion && (
            <p className="mt-1 text-[10px] text-state-connecting">{t.russianModeNoRuNodeWarning}</p>
          )}
        </div>
      )}

      {/* Bottom Section: Subscription or Guest Sign-in */}
      <div className="w-full flex flex-col gap-2 mt-auto">
        <div className="w-full rounded-2xl bg-dark-900 border border-dark-800/80 p-3.5">
          {profile?.hasActiveSubscription && sub ? (
            <>
              <div className="flex items-center justify-between text-xs text-white/80 mb-1.5">
                <span className="font-medium">{usedGb.toFixed(2)} / {limitGb.toFixed(0)} GB</span>
                <div className="flex items-center gap-1.5">
                  <span className="text-white/50">{percent.toFixed(0)}%</span>
                  <button
                    type="button"
                    onClick={refreshProfile}
                    disabled={refreshingUsage}
                    title={t.refreshUsage}
                    aria-label={t.refreshUsage}
                    className="text-white/40 hover:text-white/80 transition-colors disabled:opacity-40"
                  >
                    <svg
                      className={`h-3 w-3 ${refreshingUsage ? 'animate-spin' : ''}`}
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2.5"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <path d="M21 12a9 9 0 1 1-2.64-6.36" />
                      <path d="M21 3v6h-6" />
                    </svg>
                  </button>
                </div>
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
