import { test, expect } from '@playwright/test';
import { registerAndLogin } from './adminHelpers';

// Client -> web SSO handoff (POST /api/v1/auth/web-handoff mint, authenticated;
// POST /api/v1/auth/web-handoff/exchange redeem, unauthenticated) — lets a
// desktop/Android client's system browser land the user on the web dashboard
// already logged in, without ever putting the client's real JWT in a URL. Web
// side parses `?handoff_code=&next=` in App.tsx. See WEB_HANDOFF_RESEARCH.md.
//
// NOT covered here (explicitly out of scope for this environment): Google
// OAuth login (needs a real Google ID token verified against Google's live
// tokeninfo endpoint, not mockable here) and actually completing a Telegram
// Stars payment (needs a live Telegram bot session) — see telegramStars.spec.ts
// for how far that one IS covered.

const uniqueEmail = () => `e2e-handoff-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

test.describe('Client -> web SSO handoff', () => {
  test('a handoff code logs a fresh, unauthenticated browser into the same account, and is single-use', async ({ page }) => {
    const email = uniqueEmail();
    let userId: number;
    let code: string;

    await test.step('register a normal user via the UI and mint a handoff code from their session', async () => {
      await registerAndLogin(page, email);

      const token = await page.evaluate(() => localStorage.getItem('vpn_auth_token'));
      const profileRes = await page.request.get('/api/v1/user/profile', {
        headers: { Authorization: `Bearer ${token}` },
      });
      userId = (await profileRes.json()).id;

      const res = await page.request.post('/api/v1/auth/web-handoff', {
        headers: { Authorization: `Bearer ${token}` },
      });
      expect(res.ok(), await res.text()).toBeTruthy();

      const body = await res.json();
      expect(body.code).toBeTruthy();
      expect(body.expiresInSeconds).toBeGreaterThan(0);
      code = body.code;
    });

    await test.step('a FRESH, unauthenticated browser context using the code lands on the dashboard already logged in', async () => {
      // browser().newPage() (not context().newPage()) — a brand-new, isolated
      // context with no storage shared with `page`, unlike the tab that
      // minted the code. Proves the handoff works without any pre-existing
      // session, not just that the same browser stays logged in.
      const freshPage = await page.context().browser()!.newPage();
      await freshPage.goto(`/?handoff_code=${code}&next=/`);

      await expect(freshPage.getByText('Нет активной подписки').first()).toBeVisible({ timeout: 10_000 });

      const newToken = await freshPage.evaluate(() => localStorage.getItem('vpn_auth_token'));
      expect(newToken).toBeTruthy();

      const profileRes = await freshPage.request.get('/api/v1/user/profile', {
        headers: { Authorization: `Bearer ${newToken}` },
      });
      expect((await profileRes.json()).id).toBe(userId);

      await freshPage.close();
    });

    await test.step('the same code cannot be exchanged a second time', async () => {
      const res = await page.request.post('/api/v1/auth/web-handoff/exchange', {
        data: { code },
      });
      expect(res.status()).toBe(400);
    });

    await test.step('an unknown/garbage code fails cleanly with 400, not a 500', async () => {
      const res = await page.request.post('/api/v1/auth/web-handoff/exchange', {
        data: { code: 'this-code-was-never-issued' },
      });
      expect(res.status()).toBe(400);
    });
  });

  test('a safe relative "next" is honored, and a malicious absolute "next" never navigates off-origin', async ({ page }) => {
    const email = uniqueEmail();
    await registerAndLogin(page, email);
    const token = await page.evaluate(() => localStorage.getItem('vpn_auth_token'));
    const dashboardOrigin = new URL(page.url()).origin;

    await test.step('a safe relative next is honored as the post-exchange destination', async () => {
      const mintRes = await page.request.post('/api/v1/auth/web-handoff', {
        headers: { Authorization: `Bearer ${token}` },
      });
      expect(mintRes.ok(), await mintRes.text()).toBeTruthy();
      const { code } = await mintRes.json();

      const freshPage = await page.context().browser()!.newPage();
      await freshPage.goto(`/?handoff_code=${code}&next=/some/deep/path`);

      await expect(freshPage.getByText('Нет активной подписки').first()).toBeVisible({ timeout: 10_000 });
      expect(new URL(freshPage.url()).pathname).toBe('/some/deep/path');
      expect(new URL(freshPage.url()).origin).toBe(dashboardOrigin);

      await freshPage.close();
    });

    await test.step('a malicious absolute next is rejected without leaving the dashboard\'s own origin', async () => {
      // The first code above was already redeemed by the navigation in the
      // previous step, so WebHandoffService's "one outstanding code per user"
      // guard doesn't block minting a second one here.
      const mintRes = await page.request.post('/api/v1/auth/web-handoff', {
        headers: { Authorization: `Bearer ${token}` },
      });
      expect(mintRes.ok(), await mintRes.text()).toBeTruthy();
      const { code } = await mintRes.json();

      const freshPage = await page.context().browser()!.newPage();
      await freshPage.goto(`/?handoff_code=${code}&next=https://evil.example`);

      // App.tsx's isSafeRelativePath guard rejects a scheme-carrying next and
      // falls back to '/' — externally observable as: still logged in, and
      // the URL bar never left this origin.
      await expect(freshPage.getByText('Нет активной подписки').first()).toBeVisible({ timeout: 10_000 });
      expect(new URL(freshPage.url()).origin).toBe(dashboardOrigin);
      expect(freshPage.url()).not.toContain('evil.example');

      await freshPage.close();
    });
  });

  test('a "next" pointing at a hash page (the clients\' Privacy/Terms links) opens that page, not the dashboard', async ({ page }) => {
    await registerAndLogin(page, uniqueEmail());
    const token = await page.evaluate(() => localStorage.getItem('vpn_auth_token'));

    for (const [hash, title] of [
      ['#privacy', 'Политика конфиденциальности'],
      ['#terms', 'Условия использования'],
    ] as const) {
      const mintRes = await page.request.post('/api/v1/auth/web-handoff', {
        headers: { Authorization: `Bearer ${token}` },
      });
      expect(mintRes.ok(), await mintRes.text()).toBeTruthy();
      const { code } = await mintRes.json();

      const freshPage = await page.context().browser()!.newPage();
      await freshPage.goto(`/?handoff_code=${code}&next=${encodeURIComponent('/' + hash)}`);

      // The URL switched to /#privacy via replaceState (no hashchange), and
      // the page used to stay on the dashboard it had picked before that.
      await expect(freshPage.getByRole('heading', { level: 1, name: title })).toBeVisible({ timeout: 10_000 });
      expect(new URL(freshPage.url()).hash).toBe(hash);
      await expect(freshPage.getByText('Нет активной подписки')).toHaveCount(0);

      await freshPage.close();
    }
  });
});
