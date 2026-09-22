import { useEffect, useState } from 'react';
import LoginPage from './pages/LoginPage';
import ConnectPage from './pages/ConnectPage';
import ProfilePage from './pages/ProfilePage';
import { t } from './i18n';

type Tab = 'connect' | 'account';
type AuthPhase = 'checking' | 'loggedOut' | 'loggedIn' | 'serverUnavailable';

// The main process tags a network-level failure (server unreachable — DNS/
// connect/timeout) this way before it crosses the IPC boundary, since
// Electron only ever forwards a rejected handler's `message` string to the
// renderer, not the original error's class/properties (see ipc.ts). Without
// this, a server outage was indistinguishable from "no valid session" and
// silently dropped the user onto the login screen instead of saying what was
// actually wrong.
const NETWORK_ERROR_PREFIX = 'NETWORK_ERROR:';
const isNetworkError = (e: unknown): boolean =>
  e instanceof Error && e.message.includes(NETWORK_ERROR_PREFIX);
// A stored session that ran out and could not be renewed (see main's
// ApiClient#recoverSession) — the user has to sign in, and must not be
// dropped into a fresh trial account instead.
const isSessionExpired = (e: unknown): boolean => e instanceof Error && e.message.includes('SESSION_EXPIRED');

export default function App() {
  const [phase, setPhase] = useState<AuthPhase>('checking');
  const [isGuest, setIsGuest] = useState(false);
  const [tab, setTab] = useState<Tab>('connect');
  const [sessionExpired, setSessionExpired] = useState(false);

  useEffect(
    () =>
      window.vpnApi.onSessionExpired(() => {
        setIsGuest(false);
        setSessionExpired(true);
        setPhase('loggedOut');
      }),
    []
  );

  const checkAuth = () => {
    setPhase('checking');
    window.vpnApi
      .getProfile()
      .then((profile) => {
        setIsGuest(profile.isGuest);
        setPhase('loggedIn');
      })
      .catch((err) => {
        if (isNetworkError(err)) {
          setPhase('serverUnavailable');
          return;
        }
        if (isSessionExpired(err)) {
          setIsGuest(false);
          setSessionExpired(true);
          setPhase('loggedOut');
          return;
        }
        // No valid stored session — silently log this install into its own
        // (auto-created, trial-tariff) device account.
        window.vpnApi
          .deviceLogin()
          .then(() => {
            setIsGuest(true);
            setPhase('loggedIn');
          })
          .catch((err2) => setPhase(isNetworkError(err2) ? 'serverUnavailable' : 'loggedOut'));
      });
  };

  useEffect(checkAuth, []);

  if (phase === 'checking') {
    return <div className="flex h-screen items-center justify-center text-dark-800/60 text-sm">…</div>;
  }

  if (phase === 'serverUnavailable') {
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-3 bg-dark-950 text-white px-8 text-center">
        <p className="text-sm font-semibold">{t.serverUnavailableTitle}</p>
        <p className="text-xs text-white/60">{t.serverUnavailableBody}</p>
        <button
          type="button"
          onClick={checkAuth}
          className="mt-2 rounded-xl bg-brand-600 px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-brand-700"
        >
          {t.retry}
        </button>
      </div>
    );
  }

  if (phase === 'loggedOut') {
    return (
      <LoginPage
        isGuestSession={isGuest}
        onAuthenticated={() => {
          setSessionExpired(false);
          checkAuth();
        }}
        notice={sessionExpired ? t.sessionExpiredNotice : undefined}
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
