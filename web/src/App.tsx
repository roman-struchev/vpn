import { useState, useEffect } from 'react';
import { Lang } from './i18n';
import { UserProfile, Tariff } from './types';
import { api, getToken, removeToken } from './api';
import { Navbar } from './components/Navbar';
import { LandingView } from './components/LandingView';
import { DashboardView } from './components/DashboardView';
import { AuthModal } from './components/AuthModal';

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

  useEffect(() => {
    initApp();
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
    } catch (err) {
      removeToken();
      setUser(null);
    }
  };

  const handleLogout = () => {
    removeToken();
    setUser(null);
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
        />

        <main className="pb-16">
          {user ? (
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
