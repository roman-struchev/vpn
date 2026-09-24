import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { PASSWORD, registerAndLogin, uniqueEmail } from './adminHelpers';

function sql(statement: string): string {
  return execFileSync('docker', ['exec', 'vpn-postgres', 'psql', '-U', 'vpn_user', '-d', 'vpn_db', '-tAc', statement])
    .toString()
    .trim();
}

async function tokenOf(page: import('@playwright/test').Page) {
  return page.evaluate(() => localStorage.getItem('vpn_auth_token'));
}

test.describe('Plan states, subscription link, account', () => {
  test('a spent paid plan says so, keeps its plan, and buying again starts today', async ({ page }) => {
    const email = uniqueEmail('spent');
    await registerAndLogin(page, email);
    sql(`UPDATE users SET balance_usdt_micro = 5000000 WHERE email = '${email}'`);
    await page.reload();

    const proCard = page.locator('div.rounded-xl.bg-dark-900:has-text("Pro")').first();
    await proCard.getByRole('button', { name: 'Оплатить с баланса' }).click();
    await expect(page.getByText(/(Активна|Active) · PRO/)).toBeVisible({ timeout: 10_000 });

    // Subscription link, not a single key — and its header carries the real quota.
    await expect(page.getByRole('button', { name: 'Скопировать ссылку-подписку' })).toBeVisible();
    const profile = await (
      await page.request.get('/api/v1/user/profile', { headers: { Authorization: `Bearer ${await tokenOf(page)}` } })
    ).json();
    expect(profile.subscriptionUrl).toContain('/api/v1/subscription/export/');
    const exportRes = await page.request.get(new URL(profile.subscriptionUrl).pathname);
    expect(exportRes.headers()['subscription-userinfo']).toContain('total=107374182400');
    expect(exportRes.headers()['subscription-userinfo']).not.toContain('expire=0');

    // Low traffic warning at 90%+.
    sql(`UPDATE subscriptions SET traffic_used_bytes = traffic_limit_bytes / 100 * 95
         WHERE user_id = (SELECT id FROM users WHERE email = '${email}') AND status = 'ACTIVE'`);
    await page.reload();
    await expect(page.getByTestId('low-traffic')).toContainText('Осталось');

    // Out of traffic: still their plan, with the reason and a way forward.
    sql(`UPDATE subscriptions SET status = 'EXHAUSTED', traffic_used_bytes = traffic_limit_bytes
         WHERE user_id = (SELECT id FROM users WHERE email = '${email}') AND status = 'ACTIVE'`);
    await page.reload();
    await expect(page.getByText('Трафик закончился · PRO')).toBeVisible();
    await expect(page.getByTestId('inactive-reason')).toContainText('Трафик по тарифу закончился');
    await proCard.getByRole('button', { name: 'Купить заново — начнётся сегодня' }).click();
    await expect(page.getByText(/(Активна|Active) · PRO/)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('inactive-reason')).toHaveCount(0);
    expect(
      sql(`SELECT count(*) FROM subscriptions WHERE status = 'SUPERSEDED'
           AND user_id = (SELECT id FROM users WHERE email = '${email}')`),
    ).toBe('1');
  });

  test('an app signs in with a code from the dashboard', async ({ page }) => {
    const email = uniqueEmail('code');
    await registerAndLogin(page, email);
    await page.getByRole('button', { name: 'Получить код' }).click();
    const code = (await page.getByTestId('app-login-code').textContent())!.trim();
    expect(code).toMatch(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);

    // What the Android/desktop app sends.
    const res = await page.request.post('/api/v1/auth/code', { data: { code: code.toLowerCase() } });
    expect(res.ok()).toBeTruthy();
    expect((await res.json()).email).toBe(email);
    // Single use.
    expect((await page.request.post('/api/v1/auth/code', { data: { code } })).status()).toBe(400);
  });

  test('forgot password walks to the code step; a wrong code is refused', async ({ page }) => {
    const email = uniqueEmail('reset');
    await registerAndLogin(page, email);
    await page.getByTitle('Выйти').click();

    await page.getByRole('button', { name: 'Войти' }).first().click();
    const toLogin = page.getByRole('button', { name: 'Уже есть аккаунт? Войти' });
    if (await toLogin.isVisible().catch(() => false)) await toLogin.click();
    await page.getByRole('button', { name: 'Забыли пароль?' }).click();
    await page.getByPlaceholder('you@example.com').fill(email);
    await page.getByRole('button', { name: 'Прислать код' }).click();
    await expect(page.getByText(`Если ${email} — ваш аккаунт`)).toBeVisible();
    await page.getByPlaceholder('Код из 6 цифр').fill('000000');
    await page.getByPlaceholder('Новый пароль (от 6 символов)').fill('Another-1');
    await page.getByRole('button', { name: 'Сохранить пароль и войти' }).click();
    await expect(page.getByText(/wrong or has expired/)).toBeVisible();
  });

  test('change password, then delete the account', async ({ page }) => {
    const email = uniqueEmail('delete');
    await registerAndLogin(page, email);

    const account = page.locator('#account');
    await account.getByPlaceholder('Текущий пароль').fill(PASSWORD);
    await account.getByPlaceholder('Новый пароль (от 6 символов)').fill('Changed-1');
    await account.getByRole('button', { name: 'Сохранить' }).click();
    await expect(account.getByText('Сохранено.')).toBeVisible();
    expect((await page.request.post('/api/v1/auth/login', { data: { email, password: 'Changed-1' } })).ok()).toBeTruthy();

    await account.getByRole('button', { name: 'Удалить аккаунт' }).click();
    await expect(page.getByTestId('delete-confirm')).toContainText('сгорит');
    await account.getByRole('button', { name: 'Удалить навсегда' }).click();
    await expect(page.getByRole('button', { name: 'Попробовать бесплатно' }).first()).toBeVisible();
    expect((await page.request.post('/api/v1/auth/login', { data: { email, password: 'Changed-1' } })).ok()).toBeFalsy();
  });

  test('footer links to support and the legal pages', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('link', { name: /Поддержка: @struchev/ })).toHaveAttribute('href', 'https://t.me/struchev');
    await page.getByRole('link', { name: 'Политика конфиденциальности' }).click();
    await expect(page.getByRole('heading', { name: 'Политика конфиденциальности' })).toBeVisible();
    await page.getByRole('link', { name: 'Условия использования' }).click();
    await expect(page.getByRole('heading', { name: 'Условия использования' })).toBeVisible();
  });
});
