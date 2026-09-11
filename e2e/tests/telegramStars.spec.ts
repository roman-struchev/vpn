import { test, expect } from '@playwright/test';
import { registerAndLogin } from './adminHelpers';

// Telegram Stars top-up — web UI only, up to the boundary of what's testable
// without a real Telegram bot: proves the dashboard reaches the correct
// "waiting to link" state and produces a well-formed deep link. It does NOT
// attempt to actually complete a Telegram account-linking round trip (no live
// bot/chat in this test environment) — the server side of that
// (`/start link_<code>` handling) is already covered by
// server/src/test/java/com/vpn/server/TelegramBotServiceTest.java. Nor does it
// attempt a real Stars payment, for the same reason. See webHandoff.spec.ts
// for the note on Google OAuth being similarly out of scope.

const uniqueEmail = () => `e2e-stars-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

test.describe('Telegram Stars top-up (web UI, not-linked-yet flow)', () => {
  test('a non-Telegram user reaches the connect-Telegram step and gets a well-formed deep link', async ({ page }) => {
    const email = uniqueEmail();
    await registerAndLogin(page, email);

    await page.getByRole('button', { name: /USDT/ }).click();
    await expect(page.getByText('Пополнить баланс')).toBeVisible();

    await page.getByRole('button', { name: 'Telegram Stars', exact: true }).click();
    // starsIntro copy (i18n.ts) — not yet linked, so the "connect" explainer
    // and button render, not the Stars denomination list.
    await expect(page.getByText('Сначала привяжите Telegram-аккаунт', { exact: false })).toBeVisible();
    const connectBtn = page.getByRole('button', { name: 'Подключить Telegram' });
    await expect(connectBtn).toBeVisible();

    const [linkRes] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/api/v1/user/telegram-link') && r.request().method() === 'POST'
      ),
      connectBtn.click(),
    ]);
    expect(linkRes.ok(), await linkRes.text()).toBeTruthy();

    const { code, deepLink } = await linkRes.json();
    expect(code).toBeTruthy();
    expect(deepLink).toMatch(/^https:\/\/t\.me\/.+\?start=link_.+$/);

    // UI renders the deep link (QR + "open Telegram" link) once the fetch resolves.
    await expect(page.getByRole('link', { name: 'Открыть Telegram для подтверждения' })).toHaveAttribute(
      'href',
      deepLink
    );
    await expect(page.getByText('Ожидаем подтверждения в Telegram', { exact: false })).toBeVisible();
  });

  test('POST /api/v1/user/telegram-link directly returns the {code, deepLink} shape the UI consumes', async ({ page }) => {
    const email = uniqueEmail();
    await registerAndLogin(page, email);
    const token = await page.evaluate(() => localStorage.getItem('vpn_auth_token'));

    const res = await page.request.post('/api/v1/user/telegram-link', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(res.ok(), await res.text()).toBeTruthy();

    const body = await res.json();
    expect(typeof body.code).toBe('string');
    expect(body.code.length).toBeGreaterThan(0);
    expect(body.deepLink).toMatch(/^https:\/\/t\.me\/.+\?start=link_.+$/);
  });
});
