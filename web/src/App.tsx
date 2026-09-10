import { useState, useEffect } from 'react';
import { Lang } from './i18n';
import { UserProfile, Tariff } from './types';
import { api, getToken, removeToken } from './api';
import { Navbar } from './components/Navbar';
import { LandingView } from './components/LandingView';
import { DashboardView } from './components/DashboardView';
import { AuthModal } from './components/AuthModal';
import { AdminPanel } from './admin/AdminPanel';

declare global {
  interface Window {
    Telegram?: {
      WebApp?: {
        initData: string;
        initDataUnsafe?: any;
        expand: () => void;
        ready: () => void;
      };
    };
  }
}

export function App() {
  const [lang, setLang] = useState<Lang>('ru');
  const [user, setUser] = useState<UserProfile | null>(null);
  const [tariffs, setTariffs] = useState<Tariff[]>([]);
  const [loading, setLoading] = useState(true);

  const [isAuthOpen, setIsAuthOpen] = useState(false);
  const [isTopUpOpen, setIsTopUpOpen] = useState(false);
  // Backed by the URL hash (#admin), not just React state: a plain useState
  // resets to false on every reload (F5 while in the admin panel bounced you
  // back to the dashboard with no way to tell you'd been in admin at all).
  // #admin, or #admin/<tab> once AdminPanel starts tracking its own tab.
  const [showAdmin, setShowAdmin] = useState(() => window.location.hash.startsWith('#admin'));

  const openAdmin = () => {
    if (!window.location.hash.startsWith('#admin')) window.location.hash = 'admin';
    setShowAdmin(true);
  };

  const closeAdmin = () => {
    if (window.location.hash.startsWith('#admin')) {
      history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    setShowAdmin(false);
  };

  useEffect(() => {
    initApp();

    const onHashChange = () => setShowAdmin(window.location.hash.startsWith('#admin'));
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const initApp = async () => {
    setLoading(true);

    // 1. Check Telegram Mini App environment
    if (window.Telegram?.WebApp?.initData) {
      window.Telegram.WebApp.ready();
      window.Telegram.WebApp.expand();
      try {
        await api.telegramAuth(window.Telegram.WebApp.initData);
      } catch (err) {
        console.warn('Telegram auth failed', err);
      }
    }

    // 2. Fetch tariffs
    try {
      const tariffList = await api.getTariffs();
      setTariffs(tariffList);
    } catch (err) {
      console.error('Failed to load tariffs', err);
    }

    // 3. If token present, fetch user profile
    if (getToken()) {
      await refreshUser();
    }

    setLoading(false);
  };

  const refreshUser = async () => {
    try {
      const profile = await api.getProfile();
      setUser(profile);
      // A stale #admin hash (e.g. bookmarked, or role changed server-side)
      // shouldn't strand a non-admin viewer on a blank/guarded route.
      if (showAdmin && profile.role !== 'ADMIN') closeAdmin();
    } catch (err) {
      removeToken();
      setUser(null);
    }
  };

  const handleLogout = () => {
    removeToken();
    setUser(null);
    closeAdmin();
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-dark-900 text-slate-400">
        <div className="animate-pulse font-medium text-sm">Loading NextGen VPN...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-dark-900 text-slate-100 flex flex-col justify-between">
      <div>
        <Navbar
          lang={lang}
          setLang={setLang}
          user={user}
          onOpenAuth={() => setIsAuthOpen(true)}
          onLogout={handleLogout}
          onOpenTopUp={() => setIsTopUpOpen(true)}
          onOpenAdmin={user?.role === 'ADMIN' ? openAdmin : undefined}
        />

        <main className="pb-16">
          {user && showAdmin && user.role === 'ADMIN' ? (
            <AdminPanel lang={lang} onBack={closeAdmin} />
          ) : user ? (
            <DashboardView
              lang={lang}
              user={user}
              tariffs={tariffs}
              onRefreshUser={refreshUser}
              openTopUp={isTopUpOpen}
              setOpenTopUp={setIsTopUpOpen}
            />
          ) : (
            <LandingView
              lang={lang}
              tariffs={tariffs}
              onGetStarted={() => setIsAuthOpen(true)}
            />
          )}
        </main>
      </div>

      <footer className="border-t border-dark-800 py-6 text-center text-xs text-slate-500">
        NextGen Privacy VPN · XHTTP + Reality · Zero Logs · 2026
      </footer>

      <AuthModal
        lang={lang}
        isOpen={isAuthOpen}
        onClose={() => setIsAuthOpen(false)}
        onSuccess={refreshUser}
      />
    </div>
  );
}

export default App;
