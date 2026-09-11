import React, { useEffect, useRef, useState } from 'react';
import { Lang, translations } from '../i18n';
import { api } from '../api';

// Google Identity Services client ID (see web/src/vite-env.d.ts for how to
// set VITE_GOOGLE_CLIENT_ID). Left blank in dev/CI on purpose — the button
// below simply doesn't render until it's configured for a build.
const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';
const GOOGLE_GSI_SCRIPT_SRC = 'https://accounts.google.com/gsi/client';
const GOOGLE_GSI_SCRIPT_ID = 'google-gsi-client-script';

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: {
            client_id: string;
            callback: (response: { credential: string }) => void;
          }) => void;
          renderButton: (parent: HTMLElement, options: Record<string, unknown>) => void;
        };
      };
    };
  }
}

function loadGoogleGsiScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (window.google?.accounts?.id) {
      resolve();
      return;
    }
    const existing = document.getElementById(GOOGLE_GSI_SCRIPT_ID) as HTMLScriptElement | null;
    if (existing) {
      existing.addEventListener('load', () => resolve());
      existing.addEventListener('error', () => reject(new Error('Failed to load Google script')));
      return;
    }
    const script = document.createElement('script');
    script.id = GOOGLE_GSI_SCRIPT_ID;
    script.src = GOOGLE_GSI_SCRIPT_SRC;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('Failed to load Google script'));
    document.head.appendChild(script);
  });
}

interface AuthModalProps {
  lang: Lang;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  initialReferralCode?: string | null;
  /**
   * The tariff id the visitor clicked "Choose Plan" on from the landing page
   * (see LandingView's onGetStarted(tariffId) / App.tsx), carried through the
   * modal the same way initialReferralCode is — so the choice they made isn't
   * silently dropped by the time they land on the dashboard. See UX_REVIEW.md
   * Quick Win #9.
   */
  initialTariffId?: string | null;
}

export const AuthModal: React.FC<AuthModalProps> = ({
  lang,
  isOpen,
  onClose,
  onSuccess,
  initialReferralCode,
  initialTariffId,
}) => {
  const t = translations[lang];
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [referralCode, setReferralCode] = useState('');
  const [referralApplied, setReferralApplied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const googleButtonRef = useRef<HTMLDivElement>(null);

  // handleGoogleCredential is registered once with Google Identity Services
  // (see the effect below) but must always see the latest referralCode /
  // onSuccess / onClose, so it reads them off a ref rather than being
  // re-registered on every keystroke.
  const latestRef = useRef({ referralCode, onSuccess, onClose });
  latestRef.current = { referralCode, onSuccess, onClose };

  // A referral link (?ref=CODE, or a forwarded Telegram-style ?start=CODE)
  // should land straight on a pre-filled register form — asking a new user
  // to re-type a code they just clicked through is how referrals get lost.
  useEffect(() => {
    if (initialReferralCode) {
      setReferralCode(initialReferralCode);
      setReferralApplied(true);
      setIsRegister(true);
    }
  }, [initialReferralCode]);

  useEffect(() => {
    if (!isOpen || !GOOGLE_CLIENT_ID || !googleButtonRef.current) return;

    let cancelled = false;

    loadGoogleGsiScript()
      .then(() => {
        if (cancelled || !window.google?.accounts?.id || !googleButtonRef.current) return;
        window.google.accounts.id.initialize({
          client_id: GOOGLE_CLIENT_ID,
          callback: async (response) => {
            const { referralCode: code, onSuccess: success, onClose: close } = latestRef.current;
            setError(null);
            setLoading(true);
            try {
              await api.googleAuth(response.credential, code || undefined);
              success();
              close();
            } catch (err: any) {
              setError(err.message || 'Authentication error');
            } finally {
              setLoading(false);
            }
          },
        });
        googleButtonRef.current.innerHTML = '';
        window.google.accounts.id.renderButton(googleButtonRef.current, {
          theme: 'filled_black',
          size: 'large',
          shape: 'pill',
          width: 300,
          text: isRegister ? 'signup_with' : 'signin_with',
        });
      })
      .catch((err) => {
        console.error('Failed to load Google Sign-In', err);
      });

    return () => {
      cancelled = true;
    };
  }, [isOpen, isRegister]);

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
            {isRegister ? t.register : t.login}
          </h3>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-white text-xs"
          >
            ✕
          </button>
        </div>

        {initialTariffId && (
          <p className="-mt-2 text-[11px] font-semibold text-brand-400">
            {t.signingUpForPlan.replace('{plan}', initialTariffId.toUpperCase())}
          </p>
        )}

        {error && (
          <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-xs">
            {error}
          </div>
        )}

        {GOOGLE_CLIENT_ID && (
          <>
            <div className="flex justify-center" ref={googleButtonRef} />
            <div className="flex items-center gap-2 text-[11px] text-slate-500">
              <div className="h-px flex-1 bg-dark-800" />
              <span>{lang === 'ru' ? 'или' : 'or'}</span>
              <div className="h-px flex-1 bg-dark-800" />
            </div>
          </>
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
              <label className="block text-xs text-slate-400 mb-1">{t.referralCodeOptional}</label>
              <input
                type="text"
                placeholder="ABCD1234"
                value={referralCode}
                onChange={(e) => {
                  setReferralCode(e.target.value);
                  setReferralApplied(false);
                }}
                className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
              />
              {referralApplied && referralCode && (
                <p className="mt-1 text-[11px] text-emerald-400">
                  {lang === 'ru'
                    ? 'Реферальный код применён автоматически'
                    : 'Referral code applied automatically'}
                </p>
              )}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full mt-2 py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs transition-colors"
          >
            {loading ? '...' : isRegister ? t.createAccount : t.login}
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
            {isRegister ? t.alreadyHaveAccount : t.noAccountRegister}
          </button>
        </div>
      </div>
    </div>
  );
};
