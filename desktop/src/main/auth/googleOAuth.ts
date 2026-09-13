import { createHash, randomBytes } from 'node:crypto';
import http from 'node:http';
import { shell } from 'electron';

const AUTH_ENDPOINT = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token';

/** Give up if the user never finishes (or abandons) the browser flow. */
const FLOW_TIMEOUT_MS = 5 * 60 * 1000;

export class GoogleAuthError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GoogleAuthError';
  }
}

function base64url(input: Buffer): string {
  return input.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function clientConfigFromEnv(): { clientId: string; clientSecret: string } {
  return {
    clientId: process.env.GOOGLE_DESKTOP_CLIENT_ID || '',
    clientSecret: process.env.GOOGLE_DESKTOP_CLIENT_SECRET || '',
  };
}

/**
 * Runs Google's OAuth 2.0 "installed application" loopback flow end-to-end
 * and resolves with the resulting Google ID token (a JWT), ready to hand to
 * the server's `POST /api/v1/auth/google`.
 *
 * Google's consent screen refuses to render inside Electron's embedded
 * BrowserWindow (it's fingerprinted as an embedded webview and blocked
 * outright with an explicit warning), so this opens the user's system
 * default browser instead (`shell.openExternal`, same as the rest of this
 * app) and receives the redirect on a short-lived localhost HTTP server —
 * this is Google's own documented pattern for installed apps:
 * https://developers.google.com/identity/protocols/oauth2/native-app
 *
 * Uses PKCE (S256) in addition to the client_secret: PKCE is the part that
 * actually secures a public/installed client against a stolen auth code —
 * the "Desktop app" client_secret is not treated as confidential by Google
 * for this client type, but the flow still requires it be sent.
 */
export async function runGoogleLoginFlow(): Promise<string> {
  const { clientId, clientSecret } = clientConfigFromEnv();
  if (!clientId || !clientSecret) {
    throw new GoogleAuthError(
      'Google sign-in is not configured on this build (missing GOOGLE_DESKTOP_CLIENT_ID / GOOGLE_DESKTOP_CLIENT_SECRET).'
    );
  }

  const state = base64url(randomBytes(16));
  const codeVerifier = base64url(randomBytes(32));
  const codeChallenge = base64url(createHash('sha256').update(codeVerifier).digest());

  const { code, redirectUri } = await receiveAuthorizationCode(clientId, state, codeChallenge);
  return exchangeCodeForIdToken({ clientId, clientSecret, code, codeVerifier, redirectUri });
}

/**
 * Starts a one-shot HTTP server on 127.0.0.1 (OS-assigned free port), opens
 * the system browser to Google's consent screen pointed at that server's
 * `/callback`, and resolves with the `code` Google redirects back with.
 * Always tears the server down before settling, on success, failure, or
 * timeout.
 */
function receiveAuthorizationCode(
  clientId: string,
  state: string,
  codeChallenge: string
): Promise<{ code: string; redirectUri: string }> {
  return new Promise((resolve, reject) => {
    let settled = false;
    let redirectUri = '';

    const server = http.createServer((req, res) => {
      const url = req.url ? new URL(req.url, 'http://127.0.0.1') : null;
      if (!url || url.pathname !== '/callback') {
        res.writeHead(404).end();
        return;
      }

      const returnedState = url.searchParams.get('state');
      const code = url.searchParams.get('code');
      const oauthError = url.searchParams.get('error');
      const ok = !!code && !oauthError && returnedState === state;

      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(closePageHtml(ok));

      if (ok) {
        finish(() => resolve({ code: code as string, redirectUri }));
      } else if (oauthError) {
        finish(() => reject(new GoogleAuthError(`Google sign-in was cancelled or denied (${oauthError}).`)));
      } else {
        finish(() => reject(new GoogleAuthError('Google sign-in failed: invalid response from Google.')));
      }
    });

    function finish(action: () => void): void {
      if (settled) return;
      settled = true;
      clearTimeout(timeoutHandle);
      server.close();
      action();
    }

    const timeoutHandle = setTimeout(() => {
      finish(() => reject(new GoogleAuthError('Google sign-in timed out. Please try again.')));
    }, FLOW_TIMEOUT_MS);

    server.on('error', (err) => finish(() => reject(err instanceof Error ? err : new Error(String(err)))));

    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : 0;
      redirectUri = `http://127.0.0.1:${port}/callback`;

      const authUrl = new URL(AUTH_ENDPOINT);
      authUrl.searchParams.set('client_id', clientId);
      authUrl.searchParams.set('redirect_uri', redirectUri);
      authUrl.searchParams.set('response_type', 'code');
      authUrl.searchParams.set('scope', 'openid email profile');
      authUrl.searchParams.set('state', state);
      authUrl.searchParams.set('code_challenge', codeChallenge);
      authUrl.searchParams.set('code_challenge_method', 'S256');
      authUrl.searchParams.set('access_type', 'online');
      authUrl.searchParams.set('prompt', 'select_account');

      void shell.openExternal(authUrl.toString());
    });
  });
}

async function exchangeCodeForIdToken(params: {
  clientId: string;
  clientSecret: string;
  code: string;
  codeVerifier: string;
  redirectUri: string;
}): Promise<string> {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code: params.code,
    client_id: params.clientId,
    client_secret: params.clientSecret,
    redirect_uri: params.redirectUri,
    code_verifier: params.codeVerifier,
  });

  const response = await fetch(TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });

  const text = await response.text();
  if (!response.ok) {
    throw new GoogleAuthError(`Google sign-in failed while exchanging the code for a token: ${text}`);
  }

  let parsed: { id_token?: string };
  try {
    parsed = JSON.parse(text) as { id_token?: string };
  } catch {
    throw new GoogleAuthError('Google sign-in failed: unexpected response from Google.');
  }
  if (!parsed.id_token) {
    throw new GoogleAuthError('Google sign-in failed: no ID token was returned.');
  }
  return parsed.id_token;
}

function closePageHtml(success: boolean): string {
  const message = success
    ? 'Signed in — you can close this tab and return to the app.'
    : 'Sign-in failed — you can close this tab and return to the app.';
  return `<!doctype html><html><head><meta charset="utf-8"><title>Aura VPN</title></head><body style="font-family: -apple-system, sans-serif; text-align: center; padding-top: 4rem; color: #333;"><p>${message}</p></body></html>`;
}
