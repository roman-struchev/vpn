/**
 * What went wrong signing in, as a key the renderer can translate. The
 * server answers in English prose, and Electron prefixes an IPC failure with
 * "Error invoking remote method 'auth:login': ApiError: …" — neither belongs
 * on a sign-in form. Same buckets as the Android client's LoginError.
 */
export type LoginErrorKind =
  | 'INVALID_CREDENTIALS'
  | 'EMAIL_TAKEN'
  | 'PASSWORD_TOO_SHORT'
  | 'ACCOUNT_BLOCKED'
  | 'CODE_INVALID'
  | 'NETWORK'
  | 'GENERIC';

export function classifyLoginError(message: string): LoginErrorKind {
  const m = message.toLowerCase();
  if (m.includes('invalid email or password')) return 'INVALID_CREDENTIALS';
  if (m.includes('already registered')) return 'EMAIL_TAKEN';
  if (m.includes('at least 6')) return 'PASSWORD_TOO_SHORT';
  if (m.includes('suspended') || m.includes('blocked')) return 'ACCOUNT_BLOCKED';
  // A sign-in or password-reset code that is mistyped, used or older than 10 minutes.
  if (m.includes('wrong or has expired')) return 'CODE_INVALID';
  if (m.includes('network_error') || m.includes('fetch failed')) return 'NETWORK';
  return 'GENERIC';
}
