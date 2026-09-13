import { useState } from 'react';
import { t } from '../i18n';

export default function LoginPage({
  isGuestSession,
  onAuthenticated,
  onCancel,
}: {
  /**
   * True when a guest/device-trial session is still active (e.g. reached
   * via ProfilePage's "sign in or register" — see App.tsx), so "register"
   * here should upgrade that same account in place (keeping its balance and
   * trial) rather than create an unrelated new one. "Login" always sends
   * this install's deviceUuid regardless (see apiClient.ts#login), which
   * lets the server merge the guest account into whichever existing
   * account is signed into — so no branching is needed for that mode.
   */
  isGuestSession: boolean;
  onAuthenticated: () => void;
  onCancel?: () => void;
}) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!email.trim() || !password.trim()) {
      setError(t.fieldRequired);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      if (mode === 'login') {
        await window.vpnApi.login(email.trim(), password);
      } else if (isGuestSession) {
        await window.vpnApi.upgradeGuest(email.trim(), password);
      } else {
        await window.vpnApi.register(email.trim(), password);
      }
      onAuthenticated();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  const submitWithGoogle = async () => {
    setLoading(true);
    setError(null);
    try {
      // Opens the system browser for Google's consent screen and waits for
      // the loopback callback — see main/auth/googleOAuth.ts.
      await window.vpnApi.googleLogin();
      onAuthenticated();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative flex h-screen flex-col items-center justify-center gap-4 px-8">
      {onCancel && (
        <button
          type="button"
          onClick={onCancel}
          className="absolute top-4 left-4 flex items-center gap-1.5 text-xs font-medium text-white/60 hover:text-white transition-colors"
        >
          <span>←</span>
          <span>{t.back}</span>
        </button>
      )}
      <div className="mb-2 flex h-16 w-16 items-center justify-center rounded-2xl bg-brand-600/20 text-3xl">🛡</div>
      <h1 className="text-xl font-semibold">{mode === 'login' ? t.loginTitle : t.registerTitle}</h1>

      <input
        className="w-full rounded-lg border border-dark-800 bg-dark-900 px-4 py-2.5 text-sm outline-none focus:border-brand-500"
        placeholder={t.email}
        type="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
      />
      <input
        className="w-full rounded-lg border border-dark-800 bg-dark-900 px-4 py-2.5 text-sm outline-none focus:border-brand-500"
        placeholder={t.password}
        type="password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()}
      />

      <button
        className="w-full rounded-lg bg-brand-600 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-brand-700 disabled:opacity-50"
        disabled={loading}
        onClick={submit}
      >
        {mode === 'login' ? t.login : t.register}
      </button>

      <button
        className="text-sm text-brand-500 hover:underline"
        onClick={() => setMode(mode === 'login' ? 'register' : 'login')}
      >
        {mode === 'login' ? t.toggleToRegister : t.toggleToLogin}
      </button>

      <div className="flex w-full items-center gap-3 text-xs text-white/40">
        <div className="h-px flex-1 bg-dark-800" />
        {t.orDivider}
        <div className="h-px flex-1 bg-dark-800" />
      </div>

      <button
        className="flex w-full items-center justify-center gap-2 rounded-lg border border-dark-800 bg-dark-900 py-2.5 text-sm font-semibold transition-colors hover:bg-dark-800 disabled:opacity-50"
        disabled={loading}
        onClick={submitWithGoogle}
      >
        {t.signInWithGoogle}
      </button>

      {error && <p className="text-center text-sm text-state-error">{error}</p>}
    </div>
  );
}
