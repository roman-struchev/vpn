import { useEffect, useState } from 'react';
import type { UserProfile } from '../types';
import { t } from '../i18n';

export default function ProfilePage({ onLoggedOut }: { onLoggedOut: () => void }) {
  const [profile, setProfile] = useState<UserProfile | null>(null);

  useEffect(() => {
    window.vpnApi.getProfile().then(setProfile).catch(() => undefined);
  }, []);

  const logout = async () => {
    await window.vpnApi.logout();
    onLoggedOut();
  };

  return (
    <div className="flex flex-col gap-4 px-6 py-6">
      <h1 className="text-lg font-semibold">{profile?.email}</h1>
      <p className="text-sm text-white/70">
        {t.balance}: {profile ? (profile.balanceUsdtMicro / 1_000_000).toFixed(2) : '—'} USDT
      </p>
      <div>
        <p className="text-xs uppercase text-white/40">{t.referralLink}</p>
        <p className="select-text text-sm">{profile?.referralCode}</p>
      </div>
      <button
        className="mt-4 rounded-lg border border-dark-800 py-2.5 text-sm font-semibold text-white/80 hover:bg-dark-900"
        onClick={logout}
      >
        {t.logout}
      </button>
    </div>
  );
}
