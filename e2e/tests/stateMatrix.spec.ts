import { test, expect, type APIRequestContext, type Browser } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { PASSWORD, promoteToAdmin } from './adminHelpers';

// The dashboard and the subscription export in every account state a real
// user can be in (docs/TEST_MATRIX.md, "states"). Each state is put in place
// the way it happens in production where that is cheap (register, device
// login, buying with the balance) and by SQL where it takes time (traffic
// used up, a period that ended). Checked for every state:
//   web     no page error, no "undefined"/"NaN"/"Invalid Date" on screen, no
//           sideways scroll on a phone — in ru and en, desktop and phone width;
//   export  what v2rayTun/Hiddify/Happ fetch never 500s, and its quota header
//           is made of real numbers;
//   profile the JSON the apps parse is saved to test-results/state-matrix/ for
//           desktop/test/profileContract.test.ts to run the apps' own parsing on.

function sql(statement: string): string {
  return execFileSync('docker', ['exec', 'vpn-postgres', 'psql', '-U', 'vpn_user', '-d', 'vpn_db', '-tAc', statement])
    .toString()
    .trim();
}

const OUT_DIR = path.join(__dirname, '..', 'test-results', 'state-matrix');
const uid = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const authed = (token: string) => ({ Authorization: `Bearer ${token}` });

type Account = { id: number; token: string };
type Kind = 'email' | 'guest' | 'telegram';

async function createAccount(request: APIRequestContext, kind: Kind): Promise<Account> {
  let token: string;
  if (kind === 'guest') {
    const res = await request.post('/api/v1/auth/device', {
      data: { deviceUuid: `e2e-matrix-${uid()}`, platform: 'ANDROID' },
    });
    expect(res.status(), await res.text()).toBe(200);
    token = (await res.json()).token;
  } else {
    const res = await request.post('/api/v1/auth/register', {
      data: { email: `e2e-matrix-${uid()}@example.com`, password: PASSWORD },
    });
    expect(res.status(), await res.text()).toBe(200);
    token = (await res.json()).token;
  }
  const profile = await request.get('/api/v1/user/profile', { headers: authed(token) });
  expect(profile.status()).toBe(200);
  const id = (await profile.json()).id as number;
  if (kind === 'telegram') {
    // A Telegram-only account: no email, no password (what the bot creates).
    sql(`UPDATE users SET email = NULL, password_hash = NULL,
         telegram_id = ${Math.floor(Math.random() * 1e12)} WHERE id = ${id}`);
  }
  return { id, token };
}

async function buy(request: APIRequestContext, a: Account, tariffId: string) {
  const res = await request.post('/api/v1/user/billing/purchase', {
    headers: authed(a.token),
    data: { tariffId, isAnnual: false },
  });
  expect(res.status(), `buy ${tariffId}: ${await res.text()}`).toBe(200);
}

const active = (a: Account) => `user_id = ${a.id} AND status = 'ACTIVE'`;

/** How to get an account into each state. Guests start on the trial already. */
const STATES: Record<string, (request: APIRequestContext, a: Account, kind: Kind) => Promise<void>> = {
  'no-plan': async () => {},
  trial: async (request, a, kind) => {
    if (kind !== 'guest') await buy(request, a, 'trial');
  },
  'trial-used-up': async (request, a, kind) => {
    if (kind !== 'guest') await buy(request, a, 'trial');
    sql(`UPDATE subscriptions SET status = 'EXHAUSTED', traffic_used_bytes = traffic_limit_bytes WHERE ${active(a)}`);
  },
  paid: async (request, a) => {
    sql(`UPDATE users SET balance_usdt_micro = 1000000 WHERE id = ${a.id}`);
    await buy(request, a, 'basic');
  },
  'paid-low-traffic': async (request, a) => {
    await STATES.paid(request, a, 'email');
    sql(`UPDATE subscriptions SET traffic_used_bytes = traffic_limit_bytes / 100 * 95 WHERE ${active(a)}`);
  },
  'paid-used-up': async (request, a) => {
    await STATES.paid(request, a, 'email');
    sql(`UPDATE subscriptions SET status = 'EXHAUSTED', traffic_used_bytes = traffic_limit_bytes WHERE ${active(a)}`);
  },
  'paid-expired': async (request, a) => {
    await STATES.paid(request, a, 'email');
    sql(`UPDATE subscriptions SET status = 'EXPIRED', current_period_end = now() - interval '1 day' WHERE ${active(a)}`);
  },
  'paid-renews-short': async (request, a) => {
    // Renews in two days, and the balance (spent on the purchase) cannot cover it.
    await STATES.paid(request, a, 'email');
    sql(`UPDATE subscriptions SET current_period_end = now() + interval '2 days', auto_renew = true WHERE ${active(a)}`);
  },
  'pro-downgrade-scheduled': async (request, a) => {
    sql(`UPDATE users SET balance_usdt_micro = 2000000 WHERE id = ${a.id}`);
    await buy(request, a, 'pro');
    sql(`UPDATE subscriptions SET next_tariff_id = 'basic' WHERE ${active(a)}`);
  },
  'admin-override': async (request, a, kind) => {
    // Through the admin panel's own endpoint: SQL here once hid that the
    // profile showed "until 2126" for a temporary Pro on a trial.
    if (kind !== 'guest') await buy(request, a, 'trial');
    const adminEmail = `e2e-matrix-admin-${uid()}@example.com`;
    await request.post('/api/v1/auth/register', { data: { email: adminEmail, password: PASSWORD } });
    promoteToAdmin(adminEmail);
    const login = await request.post('/api/v1/auth/login', { data: { email: adminEmail, password: PASSWORD } });
    const res = await request.post(`/api/v1/admin/users/${a.id}/subscription/temporary-tariff`, {
      headers: authed((await login.json()).token),
      data: { tariffId: 'pro', days: 7 },
    });
    expect(res.status(), await res.text()).toBe(200);
  },
};

/** Every state for a normal account; the ones that differ by account kind for the rest. */
const CASES: Array<[Kind, string]> = [
  ...Object.keys(STATES).map((s): [Kind, string] => ['email', s]),
  ['guest', 'trial'],
  ['guest', 'trial-used-up'],
  ['guest', 'paid'],
  ['telegram', 'no-plan'],
  ['telegram', 'paid'],
  ['telegram', 'paid-used-up'],
];

const VIEWPORTS = [
  { name: 'desktop', width: 1280, height: 900 },
  { name: 'phone', width: 390, height: 800 },
];

async function checkDashboard(browser: Browser, token: string, lang: 'ru' | 'en', vp: (typeof VIEWPORTS)[number], shot: string) {
  const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
  await context.addInitScript((t) => localStorage.setItem('vpn_auth_token', t), token);
  const page = await context.newPage();
  const problems: string[] = [];
  page.on('pageerror', (e) => problems.push(`page error: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) problems.push(`console: ${m.text()}`);
  });
  page.on('response', (r) => {
    if (r.url().includes('/api/') && r.status() >= 500) problems.push(`${r.status()} from ${new URL(r.url()).pathname}`);
  });

  // Not networkidle: a refused /subscription/links is never read (api.ts
  // returns [] on !ok), so the browser keeps it open and "idle" never comes.
  const history = page.waitForResponse((r) => r.url().includes('/api/v1/user/balance-history'));
  await page.goto('/');
  await expect(page.getByTitle(/Выйти|Log out|Logout/).first()).toBeVisible({ timeout: 10_000 });
  await history;
  if (lang === 'en') await page.getByRole('button', { name: 'ru', exact: true }).click();
  await page.waitForTimeout(500);

  const text = await page.locator('body').innerText();
  for (const bad of [/\bundefined\b/, /\bNaN\b/, /Invalid Date/, /\bnull\b/, /\[object Object\]/]) {
    const m = text.match(bad);
    if (m) {
      const at = text.indexOf(m[0]);
      problems.push(`"${m[0]}" on screen: …${text.slice(Math.max(0, at - 60), at + 40).replace(/\s+/g, ' ')}…`);
    }
  }
  // The trial's "no end" is stored as a date ~100 years out; it must never show.
  const farFuture = text.match(/\b2[1-9]\d\d\b/);
  if (farFuture) problems.push(`a year ${farFuture[0]} on screen`);
  // Dates follow the page's language, not the browser's (Playwright's is en-US).
  if (lang === 'ru' && /\b\d{1,2}\/\d{1,2}\/\d{4}\b/.test(text)) problems.push('US-style date on the Russian page');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  if (overflow > 1) problems.push(`scrolls sideways by ${overflow}px`);

  await page.screenshot({ path: path.join(OUT_DIR, `${shot}.png`), fullPage: true });
  await context.close();
  return problems;
}

test.describe('every account state renders and exports cleanly', () => {
  test.beforeAll(() => mkdirSync(OUT_DIR, { recursive: true }));

  for (const [kind, state] of CASES) {
    test(`${kind} / ${state}`, async ({ browser, request }) => {
      const a = await createAccount(request, kind);
      await STATES[state](request, a, kind);
      const name = `${kind}-${state}`;

      const profileRes = await request.get('/api/v1/user/profile', { headers: authed(a.token) });
      expect(profileRes.status(), 'profile').toBe(200);
      const profile = await profileRes.json();
      const tariffs = await (await request.get('/api/v1/user/tariffs', { headers: authed(a.token) })).json();
      writeFileSync(path.join(OUT_DIR, `${name}.json`), JSON.stringify({ kind, state, profile, tariffs }, null, 2));

      await test.step('subscription export (third-party clients)', async () => {
        if (!profile.subscriptionUrl) return;
        const res = await request.get(new URL(profile.subscriptionUrl).pathname);
        expect(res.status(), 'export').toBeLessThan(500);
        // The dashboard offers this link on every working plan (trial too),
        // so there it has to work — the trial used to get "paid plans only".
        if (profile.hasActiveSubscription) {
          expect(res.status(), `export on a working plan: ${await res.text()}`).toBe(200);
        }
        const info = res.headers()['subscription-userinfo'];
        if (info) {
          for (const part of info.split(';')) {
            const [k, v] = part.trim().split('=');
            expect(Number.isFinite(Number(v)), `subscription-userinfo ${k}=${v}`).toBe(true);
            // Seconds; the ~100-years-out "no end" sentinel must read as 0.
            if (k === 'expire') expect(Number(v), 'expire is not the sentinel').toBeLessThan(4_102_444_800);
          }
        }
      });

      const problems: string[] = [];
      for (const lang of ['ru', 'en'] as const) {
        for (const vp of VIEWPORTS) {
          const found = await checkDashboard(browser, a.token, lang, vp, `${name}-${lang}-${vp.name}`);
          problems.push(...found.map((p) => `[${lang} ${vp.name}] ${p}`));
        }
      }
      expect(problems, problems.join('\n')).toEqual([]);

      await test.step('what the state itself must say', async () => {
        const sub = profile.subscription;
        if (state === 'admin-override') {
          expect(sub.tariffId).toBe('pro');
          expect(sub.overrideExpiresAt, 'when the temporary Pro ends').toBeTruthy();
        }
        if (state === 'pro-downgrade-scheduled') {
          const context = await browser.newContext();
          await context.addInitScript((t) => localStorage.setItem('vpn_auth_token', t), a.token);
          const page = await context.newPage();
          await page.goto('/');
          await expect(page.getByTestId('renewal-shortfall')).toContainText('сменится на «Basic»');
          await context.close();
        }
      });
    });
  }
});

test.describe('a session the site cannot use', () => {
  const cases: Array<[string, () => Promise<string>]> = [
    ['garbage in storage', async () => 'not-a-jwt'],
    ['a token whose signature was tampered with', async () => 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.AAAA'],
  ];
  for (const [name, token] of cases) {
    test(`${name} falls back to the landing page`, async ({ browser }) => {
      const context = await browser.newContext();
      const t = await token();
      await context.addInitScript((v) => localStorage.setItem('vpn_auth_token', v), t);
      const page = await context.newPage();
      const errors: string[] = [];
      page.on('pageerror', (e) => errors.push(e.message));
      await page.goto('/');
      await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible({ timeout: 10_000 });
      expect(errors).toEqual([]);
      await context.close();
    });
  }

  test('an account deleted elsewhere signs the browser out instead of breaking', async ({ browser, request }) => {
    const a = await createAccount(request, 'email');
    sql(`DELETE FROM users WHERE id = ${a.id}`);
    const context = await browser.newContext();
    await context.addInitScript((v) => localStorage.setItem('vpn_auth_token', v), a.token);
    const page = await context.newPage();
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto('/');
    await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible({ timeout: 10_000 });
    expect(errors).toEqual([]);
    await context.close();
  });
});
