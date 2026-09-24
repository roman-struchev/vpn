import { useEffect, useState } from 'react';
import P2pRelaySection from '../components/P2pRelaySection';
import type { DeviceDto, TariffInfo, UserProfile } from '../types';
import { t } from '../i18n';
import { planSummary } from '../../../shared/planSummary';
import { inactiveReasonText } from '../planText';

function formatBytes(bytes: number): string {
  const gb = bytes / 1024 ** 3;
  return gb >= 1 ? `${gb.toFixed(2)} GB` : `${(bytes / 1024 ** 2).toFixed(1)} MB`;
}

export default function ProfilePage({
  onLoggedOut,
  onSignInOrRegister,
  isGuest = false,
}: {
  onLoggedOut: () => void;
  onSignInOrRegister?: () => void;
  isGuest?: boolean;
}) {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  // Best-effort: without it the plan card falls back to the tariff id and
  // drops the device allowance rather than showing nothing.
  const [tariffs, setTariffs] = useState<TariffInfo[] | null>(null);
  // null until the first answer. An empty array is a claim — "you have no
  // devices" — and making it before asking is how this list came to say
  // that and then contradict itself a moment later.
  const [devices, setDevices] = useState<DeviceDto[] | null>(null);
  const [copied, setCopied] = useState(false);
  const [billingError, setBillingError] = useState<string | null>(null);
  const [deviceError, setDeviceError] = useState<string | null>(null);

  const reloadDevices = () => {
    if (isGuest) return;
    window.vpnApi
      .listDevices()
      .then(setDevices)
      .catch((e) => setDeviceError(e instanceof Error ? e.message : String(e)));
  };

  const reload = () => {
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
    window.vpnApi.getTariffs().then(setTariffs).catch(() => undefined);
    reloadDevices();
  };

  useEffect(() => {
    reload();
    // "Manage billing" hands off to the web dashboard in the system browser, so
    // a plan bought there is only visible here once this page asks again.
    // Refocusing the app window is the moment the user comes back — without
    // this they returned from a successful purchase to their old plan.
    window.addEventListener('focus', reload);
    return () => window.removeEventListener('focus', reload);
  }, [isGuest]);

  const logout = async () => {
    await window.vpnApi.logout();
    onLoggedOut();
  };

  // Lands on the plans themselves (the web dashboard scrolls to #tariffs),
  // like the Android app's "Change plan" — the top of the dashboard left the
  // user hunting for the section they were sent to.
  const openWeb = async (next: string) => {
    setBillingError(null);
    try {
      await window.vpnApi.openWebHandoff(next);
    } catch (e) {
      setBillingError(e instanceof Error ? e.message : String(e));
    }
  };

  const openBilling = async () => {
    setBillingError(null);
    try {
      await window.vpnApi.openWebHandoff('/#tariffs');
    } catch (e) {
      setBillingError(e instanceof Error ? e.message : String(e));
    }
  };

  const referralLink =
    profile?.referralLink ||
    // Temporarily pointed at the test server (217.216.79.46:8080) instead of the
    // vpn.struchev.site production domain — switch back once that's live again.
    (profile?.referralCode ? `http://217.216.79.46:8080/?ref=${profile.referralCode}` : '');

  const plan = planSummary(profile, tariffs);
  const planName = plan.tariffId?.toLowerCase() === 'trial' ? t.tariffTrial : plan.planName;

  const copyReferral = async () => {
    if (!referralLink) return;
    await navigator.clipboard.writeText(referralLink);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const revokeDevice = async (id: number, deviceName: string) => {
    if (!window.confirm(t.confirmRevokeDevice.replace('%s', deviceName))) return;
    try {
      await window.vpnApi.deleteDevice(id);
      reloadDevices();
    } catch (e) {
      setDeviceError(e instanceof Error ? e.message : String(e));
    }
  };

  if (isGuest) {
    return (
      <div className="flex flex-col gap-4 px-6 py-6">
        <div className="rounded-2xl border border-brand-500/30 bg-dark-900 p-5">
          <div className="flex items-center gap-3 mb-2">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-500/10 text-brand-400 font-bold">
              ⚡
            </div>
            <div>
              <h2 className="text-base font-semibold text-white">{t.guestProfileTitle}</h2>
              <p className="text-xs text-brand-400 font-medium">{t.guestProfileBadge}</p>
            </div>
          </div>
          <p className="text-xs leading-relaxed text-white/60">{t.guestProfileDesc}</p>
          {onSignInOrRegister && (
            <button
              type="button"
              className="mt-4 w-full rounded-xl bg-brand-600 py-2.5 text-xs font-semibold text-white transition-colors hover:bg-brand-700 shadow-md"
              onClick={onSignInOrRegister}
            >
              {t.signInOrRegister}
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 px-6 py-5">
      {/* Account & plan — the same card as the Android app's account tab */}
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
        <h1 className="text-sm font-semibold text-white truncate">{profile?.email ?? '—'}</h1>
        <p className="mt-3 text-[11px] font-semibold uppercase tracking-wider text-white/50">{t.planTitle}</p>
        <p className="mt-1 text-sm font-semibold text-white" data-testid="plan-name">
          {!profile
            ? '—'
            : !plan.hasSubscription
              ? t.planNone
              : plan.isFree
                ? t.planFree.replace('%s', planName ?? '')
                : t.planPaid.replace('%s', planName ?? '').replace('%s', plan.monthlyPriceUsdt.toFixed(2))}
        </p>
        {profile && !profile.hasActiveSubscription && inactiveReasonText(plan.inactiveReason, plan.refillAt) && (
          <p data-testid="inactive-reason" className="mt-2 rounded-lg border border-state-error/30 bg-state-error/10 px-3 py-2 text-xs text-state-error">
            {inactiveReasonText(plan.inactiveReason, plan.refillAt)}
          </p>
        )}
        {plan.hasSubscription && (
          <>
            <p className="mt-2 text-xs text-white/60">
              {plan.trafficLimitBytes > 0
                ? t.planTraffic
                    .replace('%s', formatBytes(plan.trafficUsedBytes))
                    .replace('%s', formatBytes(plan.trafficLimitBytes))
                : t.planTrafficUnlimited.replace('%s', formatBytes(plan.trafficUsedBytes))}
            </p>
            {plan.trafficLimitBytes > 0 && (
              <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-dark-800">
                <div className="h-full rounded-full bg-brand-500" style={{ width: `${plan.trafficPercent}%` }} />
              </div>
            )}
            <p className="mt-2 text-xs text-white/60">
              {plan.expiresAt
                ? t.planExpiry.replace('%s', new Date(plan.expiresAt).toLocaleDateString())
                : t.planNoExpiry}
            </p>
            {plan.lowTraffic && (
              <p data-testid="low-traffic" className="mt-2 text-xs text-state-connecting">{t.planLowTraffic}</p>
            )}
            {plan.renewal && plan.expiresAt && (
              <p className={`mt-2 text-xs ${plan.renewal.shortfallUsdt > 0 ? 'text-state-connecting' : 'text-white/60'}`}>
                {plan.renewal.shortfallUsdt > 0
                  ? t.planRenewalShort
                      .replace('%s', new Date(plan.expiresAt).toLocaleDateString())
                      .replace('%s', plan.renewal.priceUsdt.toFixed(2))
                      .replace('%s', plan.renewal.shortfallUsdt.toFixed(2))
                  : plan.renewal.nextPlanName
                    ? t.planRenewalInto
                        .replace('%s', new Date(plan.expiresAt).toLocaleDateString())
                        .replace('%s', plan.renewal.nextPlanName)
                    : t.planRenewalOn.replace('%s', new Date(plan.expiresAt).toLocaleDateString())}
              </p>
            )}
          </>
        )}
        <p className="mt-2 text-xs text-white/60">
          {t.balance}:{' '}
          <span className="font-medium text-white/80">
            {profile ? (profile.balanceUsdtMicro / 1_000_000).toFixed(2) : '—'} USDT
          </span>
        </p>
        <button
          className="mt-3 w-full rounded-xl border border-dark-750 bg-dark-800 px-3 py-1.5 text-xs font-medium text-white/90 hover:bg-dark-750 transition-colors"
          onClick={() => void openBilling()}
        >
          {plan.renewal && plan.renewal.shortfallUsdt > 0
            ? t.topUpBalance
            : profile && (!plan.hasSubscription || plan.exhausted)
              ? t.choosePlan
              : t.changePlan}
        </button>
        {billingError && <p className="mt-2 text-xs text-state-error">{billingError}</p>}
      </div>

      {/* Devices Section */}
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
        <div className="flex items-center justify-between mb-2">
          <h2 className="text-xs font-semibold uppercase tracking-wider text-white/70">{t.devicesTitle}</h2>
          <span className="text-[11px] text-white/40" data-testid="device-count">
            {devices == null
              ? '—'
              : plan.maxDevices != null
                ? t.devicesCount.replace('%s', String(devices.length)).replace('%s', String(plan.maxDevices))
                : devices.length}
          </span>
        </div>
        <p className="text-[11px] text-white/45 mb-3 leading-relaxed">{t.thisDeviceAutoAdded}</p>

        <div className="flex flex-col gap-2">
          {(devices ?? []).map((d) => (
            <div key={d.id} className="flex items-center justify-between rounded-xl bg-dark-800/80 border border-dark-750/50 px-3.5 py-2.5">
              <div>
                <p className="text-xs font-medium text-white/90">{d.deviceName}</p>
                <p className="text-[10px] text-white/40">
                  {d.platform} · {t.deviceAddedOn} {new Date(d.createdAt).toLocaleDateString()}
                </p>
              </div>
              <button
                className="text-xs font-medium text-state-error/80 hover:text-state-error hover:underline transition-colors"
                onClick={() => revokeDevice(d.id, d.deviceName)}
              >
                {t.revoke}
              </button>
            </div>
          ))}
          {devices === null ? (
            <p className="text-xs text-white/40 py-1">{t.devicesLoading}</p>
          ) : devices.length === 0 ? (
            <p className="text-xs text-white/40 py-1">{t.noDevicesYet}</p>
          ) : null}
        </div>
        {deviceError && <p className="mt-2 text-xs text-state-error">{deviceError}</p>}
      </div>

      {/* Referral Program — laid out like the Android app's: what it earned,
          the code people read out loud, and one button. The full URL is not
          printed; copying it is all anyone does with it. */}
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
        <p className="text-xs font-semibold uppercase tracking-wider text-white/70 mb-2">{t.referralLink}</p>
        {/* Always rendered, with dashes until it is known: appearing only
            once loaded grew the card and shifted everything below it. */}
        <p className="rounded-xl bg-dark-800/80 border border-dark-750/50 px-3 py-2 text-xs text-brand-400 font-medium">
          {t.referralStats
            .replace('%s', profile ? String(profile.referralCount ?? 0) : '—')
            .replace('%s', profile ? ((profile.referralEarningsUsdtMicro ?? 0) / 1_000_000).toFixed(2) : '—')}
        </p>
        <p className="mt-2 text-[11px] text-white/40">
          {t.referralCodeLabel}: <span className="select-text font-mono text-white/70">{profile?.referralCode ?? '—'}</span>
        </p>
        <p className="mt-2 text-[11px] leading-relaxed text-white/45">{t.referralDesc}</p>
        <button
          type="button"
          onClick={copyReferral}
          disabled={!referralLink}
          className="mt-3 w-full rounded-xl border border-dark-750 bg-dark-800 px-3 py-1.5 text-xs font-semibold text-white/80 hover:bg-dark-750 disabled:opacity-40 transition-colors"
        >
          {copied ? t.referralLinkCopied : t.referralCopyLink}
        </button>
      </div>

      {/* P2P Relay Mode */}
      <P2pRelaySection />

      {/* The documents. Deleting the account is on the site only. */}
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4 flex flex-col gap-2 text-xs">
        <button className="text-left text-white/60 hover:text-white" onClick={() => void openWeb('/#privacy')}>
          {t.privacyPolicy}
        </button>
        <button className="text-left text-white/60 hover:text-white" onClick={() => void openWeb('/#terms')}>
          {t.termsOfUse}
        </button>
      </div>

      {/* Logout */}
      <button
        className="rounded-xl border border-dark-800 bg-dark-900/60 py-2.5 text-xs font-semibold text-white/70 hover:bg-dark-800 hover:text-white transition-colors"
        onClick={logout}
      >
        {t.logout}
      </button>
    </div>
  );
}
