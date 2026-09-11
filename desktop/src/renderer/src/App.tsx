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
  const [tab, setTab] = useState<Tab>('connect');

  useEffect(() => {
    window.vpnApi
      .getProfile()
      .then(() => setPhase('loggedIn'))
      .catch(() =>
        // No valid stored session — rather than forcing registration/login,
        // silently log this install into its own (auto-created, trial-tariff)
        // device account. LoginPage stays reachable via ProfilePage's "sign
        // in with an existing account" for anyone who wants to keep their
        // account across reinstalls/devices.
        window.vpnApi
          .deviceLogin()
          .then(() => setPhase('loggedIn'))
          .catch(() => setPhase('loggedOut'))
      );
  }, []);

  if (phase === 'checking') {
    return <div className="flex h-screen items-center justify-center text-dark-800/60 text-sm">…</div>;
  }

  if (phase === 'loggedOut') {
    return <LoginPage onAuthenticated={() => setPhase('loggedIn')} />;
  }

  return (
    <div className="flex h-screen flex-col">
      <div className="flex-1 overflow-y-auto">
        {tab === 'connect' && <ConnectPage />}
        {tab === 'devices' && <DevicesPage />}
        {tab === 'profile' && (
          <ProfilePage
            onLoggedOut={() => setPhase('loggedOut')}
            onSwitchAccount={() => setPhase('loggedOut')}
          />
        )}
      </div>

      <nav className="flex border-t border-dark-800 bg-dark-900">
        <TabButton active={tab === 'connect'} label={t.navConnect} onClick={() => setTab('connect')} />
        <TabButton active={tab === 'devices'} label={t.navDevices} onClick={() => setTab('devices')} />
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
