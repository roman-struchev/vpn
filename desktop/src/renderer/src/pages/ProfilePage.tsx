import { useEffect, useState } from 'react';
import type { UserProfile } from '../types';
import { t } from '../i18n';

// Only ever mounted for a registered account — a guest/trial profile gets
// its own single, nav-less screen (see App.tsx) that never reaches this tab.
export default function ProfilePage({ onLoggedOut }: { onLoggedOut: () => void }) {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [copied, setCopied] = useState(false);
  const [billingError, setBillingError] = useState<string | null>(null);

  useEffect(() => {
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
  }, []);

  const logout = async () => {
    await window.vpnApi.logout();
    onLoggedOut();
  };

  // Persistent "manage billing" entry point (not just the ConnectPage
  // dead-end when there's no active subscription) — same seamless
  // client->web SSO handoff, see WEB_HANDOFF_RESEARCH.md.
  const openBilling = async () => {
    setBillingError(null);
    try {
      await window.vpnApi.openWebHandoff('/');
    } catch (e) {
      setBillingError(e instanceof Error ? e.message : String(e));
    }
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
        onClick={() => void openBilling()}
      >
        {t.manageBilling}
      </button>
      {billingError && <p className="text-xs text-state-error">{billingError}</p>}
      <button
        className="rounded-lg border border-dark-800 py-2.5 text-sm font-semibold text-white/80 hover:bg-dark-900"
        onClick={logout}
      >
        {t.logout}
      </button>
    </div>
  );
}
