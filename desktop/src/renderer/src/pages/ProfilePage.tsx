import { useEffect, useState } from 'react';
import type { DeviceDto, UserProfile } from '../types';
import { t } from '../i18n';

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
  const [devices, setDevices] = useState<DeviceDto[]>([]);
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

  useEffect(() => {
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
    reloadDevices();
  }, [isGuest]);

  const logout = async () => {
    await window.vpnApi.logout();
    onLoggedOut();
  };

  const openBilling = async () => {
    setBillingError(null);
    try {
      await window.vpnApi.openWebHandoff('/');
    } catch (e) {
      setBillingError(e instanceof Error ? e.message : String(e));
    }
  };

  const referralLink =
    profile?.referralLink ||
    (profile?.referralCode ? `https://vpn.struchev.site/?ref=${profile.referralCode}` : '');

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
              <p className="text-xs text-brand-400 font-medium">Бесплатный период</p>
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
      {/* Profile & Subscription Info */}
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-sm font-semibold text-white">{profile?.email ?? '—'}</h1>
            <p className="mt-0.5 text-xs text-white/50">
              {t.balance}: <span className="font-medium text-white/80">{profile ? (profile.balanceUsdtMicro / 1_000_000).toFixed(2) : '—'} USDT</span>
            </p>
          </div>
          <button
            className="rounded-xl border border-dark-750 bg-dark-800 px-3 py-1.5 text-xs font-medium text-white/90 hover:bg-dark-750 transition-colors"
            onClick={() => void openBilling()}
          >
            {t.manageBilling}
          </button>
        </div>
        {billingError && <p className="mt-2 text-xs text-state-error">{billingError}</p>}
      </div>

      {/* Devices Section */}
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
        <div className="flex items-center justify-between mb-2">
          <h2 className="text-xs font-semibold uppercase tracking-wider text-white/70">{t.devicesTitle}</h2>
          <span className="text-[11px] text-white/40">{devices.length}</span>
        </div>
        <p className="text-[11px] text-white/45 mb-3 leading-relaxed">{t.thisDeviceAutoAdded}</p>

        <div className="flex flex-col gap-2">
          {devices.map((d) => (
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
          {devices.length === 0 && (
            <p className="text-xs text-white/40 py-1">Нет подключенных устройств</p>
          )}
        </div>
        {deviceError && <p className="mt-2 text-xs text-state-error">{deviceError}</p>}
      </div>

      {/* Referral Program */}
      <div className="rounded-2xl border border-dark-800/80 bg-dark-900 p-4">
        <p className="text-xs font-semibold uppercase tracking-wider text-white/70 mb-2">{t.referralLink}</p>
        <div className="flex items-center gap-2">
          <input
            readOnly
            value={referralLink}
            onFocus={(e) => e.currentTarget.select()}
            className="min-w-0 flex-1 select-text rounded-xl border border-dark-750 bg-dark-800 px-3 py-2 text-xs text-white/80 outline-none"
          />
          <button
            type="button"
            onClick={copyReferral}
            disabled={!referralLink}
            className="rounded-xl border border-dark-750 bg-dark-800 px-3 py-2 text-xs font-semibold text-white/80 hover:bg-dark-750 disabled:opacity-40 transition-colors"
          >
            {copied ? t.copied : t.copy}
          </button>
        </div>
        <p className="mt-1.5 text-[11px] text-white/40">
          {t.referralCodeLabel}: <span className="font-mono text-white/70">{profile?.referralCode ?? '—'}</span>
        </p>
        {profile?.referralCount !== undefined && (
          <p className="mt-2.5 rounded-xl bg-dark-800/80 border border-dark-750/50 px-3 py-2 text-xs text-brand-400 font-medium">
            {t.referralStats
              .replace('%s', String(profile.referralCount))
              .replace('%s', ((profile.referralEarningsUsdtMicro ?? 0) / 1_000_000).toFixed(2))}
          </p>
        )}
        <p className="mt-2 text-[11px] leading-relaxed text-white/45">{t.referralDesc}</p>
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
