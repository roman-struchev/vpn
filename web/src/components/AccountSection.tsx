import React, { useEffect, useState } from 'react';
import { KeyRound, LifeBuoy, Smartphone, Trash2, UserCog } from 'lucide-react';
import { Lang, translations } from '../i18n';
import { UserProfile } from '../types';
import { api } from '../api';
import { SUPPORT_HANDLE, SUPPORT_URL } from '../support';

/**
 * Everything about getting into this account and leaving it:
 * - a code to sign in to the apps (the only way in there for an account made
 *   in Telegram, which has no password);
 * - email + password: change it, or add it to a Telegram/Google account so a
 *   forgotten password can be reset and the apps can be used with it;
 * - support;
 * - deleting the account (Google Play requires a way to do it).
 */
export const AccountSection: React.FC<{
  lang: Lang;
  user: UserProfile;
  onChanged: () => void;
  onDeleted: () => void;
}> = ({ lang, user, onChanged, onDeleted }) => {
  const t = translations[lang];
  const hasEmail = !!user.email;

  const [code, setCode] = useState<string | null>(null);
  const [codeError, setCodeError] = useState<string | null>(null);

  const [email, setEmail] = useState('');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [credBusy, setCredBusy] = useState(false);
  const [credMessage, setCredMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // The apps send people here for things only the site does (deleting the
  // account): <site>/#account lands on this section, not the top of the page.
  useEffect(() => {
    if (window.location.hash.startsWith('#account')) {
      document.getElementById('account')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, []);

  const getCode = async () => {
    setCodeError(null);
    try {
      setCode((await api.getAppLoginCode()).code);
    } catch (e: any) {
      setCodeError(e.message);
    }
  };

  const saveCredentials = async (e: React.FormEvent) => {
    e.preventDefault();
    setCredBusy(true);
    setCredMessage(null);
    try {
      await api.setCredentials(hasEmail ? null : email, hasEmail ? currentPassword : null, newPassword);
      setCredMessage({ ok: true, text: t.credentialsSaved });
      setCurrentPassword('');
      setNewPassword('');
      onChanged();
    } catch (err: any) {
      setCredMessage({ ok: false, text: err.message });
    } finally {
      setCredBusy(false);
    }
  };

  const deleteAccount = async () => {
    setDeleteError(null);
    try {
      await api.deleteAccount();
      onDeleted();
    } catch (e: any) {
      setDeleteError(e.message);
    }
  };

  const input =
    'w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500';

  return (
    <div id="account" className="p-6 rounded-2xl bg-dark-850 border border-dark-800 space-y-6">
      <h3 className="font-bold text-base flex items-center gap-2">
        <UserCog className="w-4 h-4 text-brand-500" />
        <span>{t.accountTitle}</span>
      </h3>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Sign in to the apps */}
        <div className="space-y-2">
          <p className="text-xs font-semibold flex items-center gap-2">
            <Smartphone className="w-3.5 h-3.5 text-slate-400" />
            {t.appCodeTitle}
          </p>
          <p className="text-[11px] text-slate-400 leading-relaxed">{t.appCodeDesc}</p>
          {code ? (
            <p data-testid="app-login-code" className="font-mono text-lg font-bold tracking-widest text-brand-400">
              {code}
            </p>
          ) : null}
          <button
            onClick={getCode}
            className="px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-xs font-semibold"
          >
            {code ? t.appCodeAgain : t.appCodeGet}
          </button>
          {codeError && <p className="text-[11px] text-red-400">{codeError}</p>}
        </div>

        {/* Email & password */}
        <form onSubmit={saveCredentials} className="space-y-2">
          <p className="text-xs font-semibold flex items-center gap-2">
            <KeyRound className="w-3.5 h-3.5 text-slate-400" />
            {hasEmail ? t.changePasswordTitle : t.addEmailTitle}
          </p>
          <p className="text-[11px] text-slate-400 leading-relaxed">
            {hasEmail ? user.email : t.addEmailDesc}
          </p>
          {!hasEmail && (
            <input type="email" required placeholder="you@example.com" value={email}
              onChange={(e) => setEmail(e.target.value)} className={input} />
          )}
          {hasEmail && (
            <input type="password" required autoComplete="current-password" placeholder={t.currentPasswordLabel}
              value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} className={input} />
          )}
          <input type="password" required minLength={6} autoComplete="new-password" placeholder={t.newPasswordLabel}
            value={newPassword} onChange={(e) => setNewPassword(e.target.value)} className={input} />
          <button type="submit" disabled={credBusy}
            className="px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-xs font-semibold">
            {credBusy ? '...' : t.saveLabel}
          </button>
          {credMessage && (
            <p className={`text-[11px] ${credMessage.ok ? 'text-emerald-400' : 'text-red-400'}`}>{credMessage.text}</p>
          )}
        </form>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 pt-4 border-t border-dark-800">
        <a href={SUPPORT_URL} target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-2 text-xs text-slate-300 hover:text-white">
          <LifeBuoy className="w-4 h-4 text-brand-500" />
          {t.supportLabel}: <span className="text-brand-500">{SUPPORT_HANDLE}</span>
        </a>
        {!confirmDelete ? (
          <button onClick={() => setConfirmDelete(true)}
            className="inline-flex items-center gap-1.5 text-[11px] text-slate-500 hover:text-red-400">
            <Trash2 className="w-3.5 h-3.5" />
            {t.deleteAccount}
          </button>
        ) : (
          <div data-testid="delete-confirm" className="w-full p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-xs text-red-300 space-y-2">
            <p>
              {t.deleteAccountWarning.replace('{balance}', (user.balanceUsdtMicro / 1_000_000).toFixed(2))}
            </p>
            <div className="flex gap-2">
              <button onClick={deleteAccount}
                className="px-3 py-1 rounded-lg bg-red-500/30 hover:bg-red-500/40 text-red-100 font-bold">
                {t.deleteAccountConfirm}
              </button>
              <button onClick={() => setConfirmDelete(false)} className="px-3 py-1 rounded-lg text-slate-300 hover:text-white">
                {t.cancelLabel}
              </button>
            </div>
            {deleteError && <p className="text-red-400">{deleteError}</p>}
          </div>
        )}
      </div>
    </div>
  );
};
