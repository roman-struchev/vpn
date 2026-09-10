import React from 'react';
import { Shield, Globe, LogIn, LogOut, Wallet, ShieldAlert } from 'lucide-react';
import { Lang, translations } from '../i18n';
import { UserProfile } from '../types';

interface NavbarProps {
  lang: Lang;
  setLang: (lang: Lang) => void;
  user: UserProfile | null;
  onOpenAuth: () => void;
  onLogout: () => void;
  onOpenTopUp: () => void;
  /** Present only for ADMIN-role users — entry point into the admin panel. */
  onOpenAdmin?: () => void;
}

export const Navbar: React.FC<NavbarProps> = ({
  lang,
  setLang,
  user,
  onOpenAuth,
  onLogout,
  onOpenTopUp,
  onOpenAdmin,
}) => {
  const t = translations[lang];

  return (
    <header className="sticky top-0 z-40 bg-dark-900/80 backdrop-blur-md border-b border-dark-800">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-10 h-10 rounded-xl bg-brand-500/10 border border-brand-500/20 flex items-center justify-center text-brand-500">
            <Shield className="w-5 h-5" />
          </div>
          <span className="font-bold text-lg tracking-tight bg-gradient-to-r from-white via-slate-200 to-slate-400 bg-clip-text text-transparent">
            {t.brandName}
          </span>
        </div>

        <div className="flex items-center gap-3">
          {/* Language Switcher */}
          <button
            onClick={() => setLang(lang === 'ru' ? 'en' : 'ru')}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-850 text-xs font-medium text-slate-300 transition-colors border border-dark-800"
          >
            <Globe className="w-3.5 h-3.5" />
            <span className="uppercase">{lang}</span>
          </button>

          {user ? (
            <div className="flex items-center gap-2">
              {onOpenAdmin && (
                <button
                  onClick={onOpenAdmin}
                  data-testid="nav-admin-link"
                  className="p-2 rounded-lg bg-dark-800 hover:bg-brand-500/10 hover:text-brand-500 text-slate-400 transition-colors border border-dark-800"
                  title="Admin"
                >
                  <ShieldAlert className="w-4 h-4" />
                </button>
              )}
              <button
                onClick={onOpenTopUp}
                className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 border border-emerald-500/20 text-emerald-400 text-xs font-semibold transition-colors"
              >
                <Wallet className="w-3.5 h-3.5" />
                <span>${(user.balanceUsdtMicro / 1_000_000).toFixed(2)} USDT</span>
              </button>
              <button
                onClick={onLogout}
                className="p-2 rounded-lg bg-dark-800 hover:bg-red-500/10 hover:text-red-400 text-slate-400 transition-colors border border-dark-800"
                title={t.logout}
              >
                <LogOut className="w-4 h-4" />
              </button>
            </div>
          ) : (
            <button
              onClick={onOpenAuth}
              className="flex items-center gap-1.5 px-4 py-1.5 rounded-lg bg-brand-500 hover:bg-brand-600 text-dark-950 font-semibold text-xs tracking-wide transition-all shadow-lg shadow-brand-500/10"
            >
              <LogIn className="w-3.5 h-3.5" />
              <span>{t.login}</span>
            </button>
          )}
        </div>
      </div>
    </header>
  );
};
