import { useEffect, useState } from 'react';
import LoginPage from './pages/LoginPage';
import ConnectPage from './pages/ConnectPage';
import DevicesPage from './pages/DevicesPage';
import ProfilePage from './pages/ProfilePage';
import { t } from './i18n';

type Tab = 'connect' | 'devices' | 'profile';
type AuthPhase = 'checking' | 'loggedOut' | 'loggedIn';

export default function App() {
  const [phase, setPhase] = useState<AuthPhase>('checking');
  // Guest = no-signup device-trial account (see deviceLogin), not one the
  // user consciously created. Tracked at this level (not just inside
  // ProfilePage) because it also decides which nav tabs make sense (no
  // "Devices" for a single-device trial profile) and how LoginPage should
  // handle "register" (upgrade this session in place vs. create a new one).
  const [isGuest, setIsGuest] = useState(false);
  const [tab, setTab] = useState<Tab>('connect');

  const checkAuth = () => {
    setPhase('checking');
    window.vpnApi
      .getProfile()
      .then((profile) => {
        setIsGuest(profile.isGuest);
        setPhase('loggedIn');
      })
      .catch(() =>
        // No valid stored session — rather than forcing registration/login,
        // silently log this install into its own (auto-created, trial-tariff)
        // device account. LoginPage stays reachable via ProfilePage's "sign
        // in or register" for anyone who wants to keep their account across
        // reinstalls/devices.
        window.vpnApi
          .deviceLogin()
          .then(() => {
            setIsGuest(true);
            setPhase('loggedIn');
          })
          .catch(() => setPhase('loggedOut'))
      );
  };

  useEffect(checkAuth, []);

  if (phase === 'checking') {
    return <div className="flex h-screen items-center justify-center text-dark-800/60 text-sm">…</div>;
  }

  if (phase === 'loggedOut') {
    // isGuest still reflects whatever session we had before landing here —
    // set on a genuine guest→LoginPage handoff (ProfilePage's CTA doesn't
    // clear the stored session), and false on a real logout or a first-run
    // deviceLogin failure, where there's no session to upgrade from.
    return <LoginPage isGuestSession={isGuest} onAuthenticated={checkAuth} />;
  }

  return (
    <div className="flex h-screen flex-col">
      <div className="flex-1 overflow-y-auto">
        {tab === 'connect' && <ConnectPage />}
        {tab === 'devices' && <DevicesPage isGuest={isGuest} />}
        {tab === 'profile' && (
          <ProfilePage
            onLoggedOut={() => setPhase('loggedOut')}
            onSwitchAccount={() => setPhase('loggedOut')}
          />
        )}
      </div>

      <nav className="flex border-t border-dark-800 bg-dark-900">
        <TabButton active={tab === 'connect'} label={t.navConnect} onClick={() => setTab('connect')} />
        {!isGuest && (
          <TabButton active={tab === 'devices'} label={t.navDevices} onClick={() => setTab('devices')} />
        )}
        <TabButton active={tab === 'profile'} label={t.navProfile} onClick={() => setTab('profile')} />
      </nav>
    </div>
  );
}

function TabButton({ active, label, onClick }: { active: boolean; label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`flex-1 py-3 text-sm font-medium transition-colors ${
        active ? 'text-brand-500' : 'text-white/50 hover:text-white/80'
      }`}
    >
      {label}
    </button>
  );
}
