import { useState } from 'react';
import { t } from '../i18n';
import { classifyLoginError } from '../../../shared/loginError';
import { SUPPORT_HANDLE, SUPPORT_URL } from '../support';

/** The server's English prose (behind Electron's IPC prefix) as a message in the app's language. */
function loginErrorText(e: unknown): string {
  const kind = classifyLoginError(e instanceof Error ? e.message : String(e));
  switch (kind) {
    case 'INVALID_CREDENTIALS':
      return t.loginErrorInvalidCredentials;
    case 'EMAIL_TAKEN':
      return t.loginErrorEmailTaken;
    case 'PASSWORD_TOO_SHORT':
      return t.loginErrorPasswordShort;
    case 'ACCOUNT_BLOCKED':
      return t.loginErrorBlocked;
    case 'CODE_INVALID':
      return t.loginErrorCodeInvalid;
    case 'NETWORK':
      return t.loginErrorNetwork;
    default:
      return t.loginErrorGeneric;
  }
}

export default function LoginPage({
  isGuestSession,
  onAuthenticated,
  onCancel,
  notice,
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
  /** Shown above the form, e.g. why the user is here again (their session expired). */
  notice?: string;
}) {
  // 'code': a one-time code from the Telegram bot or the web dashboard — the
  // way in for an account made in Telegram (it has no password).
  // 'reset': forgotten password, a code to Telegram/email then a new one.
  const [mode, setMode] = useState<'login' | 'register' | 'code' | 'reset'>('login');
  const [code, setCode] = useState('');
  const [resetSent, setResetSent] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    // The button is disabled while a request is out, but Enter in the password
    // field is not — without this, holding Enter fired repeated registrations.
    if (loading) return;
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
      setError(loginErrorText(e));
    } finally {
      setLoading(false);
    }
  };

  const run = async (action: () => Promise<void>) => {
    if (loading) return;
    setLoading(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(loginErrorText(e));
    } finally {
      setLoading(false);
    }
  };

  const submitCode = () => {
    if (!code.trim()) return setError(t.fieldRequired);
    return run(async () => {
      await window.vpnApi.loginWithCode(code.trim());
      onAuthenticated();
    });
  };

  const submitReset = () => {
    if (!email.trim() || (resetSent && (!code.trim() || !password))) return setError(t.fieldRequired);
    return run(async () => {
      if (!resetSent) {
        await window.vpnApi.requestPasswordReset(email.trim());
        setResetSent(true);
        return;
      }
      await window.vpnApi.confirmPasswordReset(email.trim(), code.trim(), password);
      onAuthenticated();
    });
  };

  const switchMode = (next: 'login' | 'register' | 'code' | 'reset') => {
    setMode(next);
    setError(null);
    setCode('');
    setResetSent(false);
  };

  const input =
    'w-full rounded-lg border border-dark-800 bg-dark-900 px-4 py-2.5 text-sm outline-none focus:border-brand-500';

  if (mode === 'code' || mode === 'reset') {
    return (
      <div className="relative flex h-screen flex-col items-center justify-center gap-4 px-8">
        <button
          type="button"
          onClick={() => switchMode('login')}
          className="absolute top-4 left-4 flex items-center gap-1.5 text-xs font-medium text-white/60 hover:text-white transition-colors"
        >
          <span>←</span>
          <span>{t.back}</span>
        </button>
        <div className="mb-2 flex h-16 w-16 items-center justify-center rounded-2xl bg-brand-600/20 text-3xl">🛡</div>
        <h1 className="text-xl font-semibold">{mode === 'code' ? t.codeLoginTitle : t.resetTitle}</h1>
        <p className="text-center text-xs leading-relaxed text-white/60">
          {mode === 'code' ? t.codeLoginHint : resetSent ? t.resetCodeSent.replace('%s', email.trim()) : t.resetIntro}
        </p>
        {mode === 'code' ? (
          <input
            className={`${input} text-center font-mono tracking-widest uppercase`}
            placeholder="XXXX-XXXX"
            value={code}
            autoFocus
            onChange={(e) => setCode(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && submitCode()}
          />
        ) : !resetSent ? (
          <input className={input} placeholder={t.email} type="email" value={email}
            onChange={(e) => setEmail(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submitReset()} />
        ) : (
          <>
            <input className={`${input} tracking-widest`} placeholder={t.resetCodeLabel} inputMode="numeric"
              value={code} onChange={(e) => setCode(e.target.value)} />
            <input className={input} placeholder={t.newPasswordLabel} type="password" value={password}
              onChange={(e) => setPassword(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submitReset()} />
          </>
        )}
        <button
          className="w-full rounded-lg bg-brand-600 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-brand-700 disabled:opacity-50"
          disabled={loading}
          onClick={mode === 'code' ? submitCode : submitReset}
        >
          {mode === 'code' ? t.login : resetSent ? t.resetSave : t.resetSendCode}
        </button>
        <p className="text-center text-[11px] text-white/40">
          {t.needHelp}{' '}
          <button className="text-brand-500 hover:underline" onClick={() => void window.vpnApi.openExternal(SUPPORT_URL)}>
            {SUPPORT_HANDLE}
          </button>
        </p>
        {error && <p className="text-center text-sm text-state-error">{error}</p>}
      </div>
    );
  }

  const submitWithGoogle = async () => {
    setLoading(true);
    setError(null);
    try {
      // Opens the system browser for Google's consent screen and waits for
      // the loopback callback — see main/auth/googleOAuth.ts.
      await window.vpnApi.googleLogin();
      onAuthenticated();
    } catch (e) {
      setError(loginErrorText(e));
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

      <div className="flex w-full items-center justify-between">
        <button
          className="text-sm text-brand-500 hover:underline"
          onClick={() => switchMode(mode === 'login' ? 'register' : 'login')}
        >
          {mode === 'login' ? t.toggleToRegister : t.toggleToLogin}
        </button>
        {mode === 'login' && (
          <button className="text-xs text-white/50 hover:text-white" onClick={() => switchMode('reset')}>
            {t.forgotPassword}
          </button>
        )}
      </div>

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

      <button
        className="w-full rounded-lg border border-dark-800 bg-dark-900 py-2.5 text-sm font-semibold transition-colors hover:bg-dark-800 disabled:opacity-50"
        disabled={loading}
        onClick={() => switchMode('code')}
      >
        {t.signInWithCode}
      </button>

      {error && <p className="text-center text-sm text-state-error">{error}</p>}
      {notice && !error && <p className="text-center text-sm text-state-connecting">{notice}</p>}
    </div>
  );
}
