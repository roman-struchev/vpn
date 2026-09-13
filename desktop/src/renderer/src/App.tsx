import { useEffect, useState } from 'react';
import LoginPage from './pages/LoginPage';
import ConnectPage from './pages/ConnectPage';
import ProfilePage from './pages/ProfilePage';
import { t } from './i18n';

type Tab = 'connect' | 'account';
type AuthPhase = 'checking' | 'loggedOut' | 'loggedIn';

export default function App() {
  const [phase, setPhase] = useState<AuthPhase>('checking');
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
        // No valid stored session — silently log this install into its own
        // (auto-created, trial-tariff) device account.
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
    return (
      <LoginPage
        isGuestSession={isGuest}
        onAuthenticated={checkAuth}
        onCancel={isGuest ? () => setPhase('loggedIn') : undefined}
      />
    );
  }

  const isMac = window.vpnApi?.platform === 'darwin' || navigator.userAgent.includes('Mac');

  return (
    <div className="flex h-screen flex-col bg-dark-950 text-white select-none overflow-hidden">
      {/* Top Header Bar with macOS traffic lights space and segmented pill switcher */}
      <header className="drag-region flex items-center justify-between h-14 border-b border-dark-800/80 bg-dark-950/80 backdrop-blur-md px-4 shrink-0">
        <div className={`flex items-center gap-2 ${isMac ? 'pl-[68px]' : ''}`}>
          <span className="text-xs font-bold tracking-wider text-white/90 uppercase">Aura VPN</span>
        </div>

        {/* Segmented Pill Switcher [ Подключение | Аккаунт ] */}
        <div className="no-drag flex items-center p-0.5 rounded-full bg-dark-900 border border-dark-800">
          <button
            type="button"
            onClick={() => setTab('connect')}
            className={`px-3.5 py-1 rounded-full text-xs font-medium transition-all duration-200 ${
              tab === 'connect'
                ? 'bg-dark-800 text-white shadow-sm font-semibold'
                : 'text-white/50 hover:text-white/80'
            }`}
          >
            {t.navConnect}
          </button>
          <button
            type="button"
            onClick={() => setTab('account')}
            className={`px-3.5 py-1 rounded-full text-xs font-medium transition-all duration-200 ${
              tab === 'account'
                ? 'bg-dark-800 text-white shadow-sm font-semibold'
                : 'text-white/50 hover:text-white/80'
            }`}
          >
            {t.navAccount}
          </button>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto">
        {tab === 'connect' && (
          <ConnectPage
            isGuest={isGuest}
            onSignInOrRegister={() => setPhase('loggedOut')}
          />
        )}
        {tab === 'account' && (
          <ProfilePage
            isGuest={isGuest}
            onSignInOrRegister={() => setPhase('loggedOut')}
            onLoggedOut={() => setPhase('loggedOut')}
          />
        )}
      </main>
    </div>
  );
}
