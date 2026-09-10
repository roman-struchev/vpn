import React, { useState } from 'react';
import { Lang, translations } from '../i18n';
import { api } from '../api';

interface AuthModalProps {
  lang: Lang;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

export const AuthModal: React.FC<AuthModalProps> = ({
  lang,
  isOpen,
  onClose,
  onSuccess,
}) => {
  const t = translations[lang];
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [referralCode, setReferralCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      if (isRegister) {
        await api.register(email, password, referralCode || undefined);
      } else {
        await api.login(email, password);
      }
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message || 'Authentication error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-sm w-full space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="font-bold text-base">
            {isRegister ? 'Регистрация' : t.login}
          </h3>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-white text-xs"
          >
            ✕
          </button>
        </div>

        {error && (
          <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-xs">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="block text-xs text-slate-400 mb-1">Email</label>
            <input
              type="email"
              required
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
            />
          </div>

          <div>
            <label className="block text-xs text-slate-400 mb-1">Password</label>
            <input
              type="password"
              required
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
            />
          </div>

          {isRegister && (
            <div>
              <label className="block text-xs text-slate-400 mb-1">Referral Code (Optional)</label>
              <input
                type="text"
                placeholder="ref_..."
                value={referralCode}
                onChange={(e) => setReferralCode(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
              />
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full mt-2 py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs transition-colors"
          >
            {loading ? '...' : isRegister ? 'Создать аккаунт' : t.login}
          </button>
        </form>

        <div className="text-center pt-2 border-t border-dark-800">
          <button
            onClick={() => {
              setIsRegister(!isRegister);
              setError(null);
            }}
            className="text-xs text-slate-400 hover:text-white"
          >
            {isRegister
              ? 'Уже есть аккаунт? Войти'
              : 'Нет аккаунта? Зарегистрироваться'}
          </button>
        </div>
      </div>
    </div>
  );
};
