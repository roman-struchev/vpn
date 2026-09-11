import { useEffect, useState } from 'react';
import type { UserProfile } from '../types';
import { t } from '../i18n';

export default function ProfilePage({
  onLoggedOut,
  onSwitchAccount,
}: {
  onLoggedOut: () => void;
  /**
   * Sends the user to LoginPage without clearing the current session — for
   * a trial device account that wants to register/sign in with a real
   * account instead (e.g. to keep it across reinstalls/devices). Unlike
   * logout(), this doesn't touch the stored token/device UUID.
   */
  onSwitchAccount: () => void;
}) {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
  }, []);

  const logout = async () => {
    await window.vpnApi.logout();
    onLoggedOut();
  };

  // A bare referral code is useless to hand to a friend — they'd have to be told
  // where to type it. The server builds the full `<site>/?ref=CODE` link, which
  // any invitee can just open in a browser regardless of platform (no Telegram
  // required); fall back to composing it from the code for older servers.
  const referralLink =
    profile?.referralLink ||
    (profile?.referralCode ? `https://vpn.struchev.site/?ref=${profile.referralCode}` : '');

  const copyReferral = async () => {
    if (!referralLink) return;
    await navigator.clipboard.writeText(referralLink);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="flex flex-col gap-4 px-6 py-6">
      <h1 className="text-lg font-semibold">{profile?.email}</h1>
      <p className="text-sm text-white/70">
        {t.balance}: {profile ? (profile.balanceUsdtMicro / 1_000_000).toFixed(2) : '—'} USDT
      </p>
      <div>
        <p className="text-xs uppercase text-white/40">{t.referralLink}</p>
        <div className="mt-1 flex items-center gap-2">
          <input
            readOnly
            value={referralLink}
            onFocus={(e) => e.currentTarget.select()}
            className="min-w-0 flex-1 select-text rounded-lg border border-dark-800 bg-dark-900 px-2 py-1.5 text-xs text-white/80 outline-none"
          />
          <button
            type="button"
            onClick={copyReferral}
            disabled={!referralLink}
            className="rounded-lg border border-dark-800 px-3 py-1.5 text-xs font-semibold text-white/80 hover:bg-dark-900 disabled:opacity-40"
          >
            {copied ? t.copied : t.copy}
          </button>
        </div>
        <p className="mt-1 text-[11px] text-white/40">
          {t.referralCodeLabel}: {profile?.referralCode ?? '—'}
        </p>
        <p className="mt-2 text-[11px] leading-relaxed text-white/50">{t.referralDesc}</p>
      </div>
      <button
        className="mt-4 rounded-lg border border-dark-800 py-2.5 text-sm font-semibold text-white/80 hover:bg-dark-900"
        onClick={onSwitchAccount}
      >
        {t.signInExistingAccount}
      </button>
      <button
        className="rounded-lg border border-dark-800 py-2.5 text-sm font-semibold text-white/80 hover:bg-dark-900"
        onClick={logout}
      >
        {t.logout}
      </button>
    </div>
  );
}
