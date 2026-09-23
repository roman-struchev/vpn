import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { registerAndLogin, uniqueEmail } from './adminHelpers';

function seedPromoCode(code: string, bonusMicro: number) {
  execFileSync('docker', [
    'exec',
    'vpn-postgres',
    'psql',
    '-U',
    'vpn_user',
    '-d',
    'vpn_db',
    '-c',
    `INSERT INTO promo_codes (code, bonus_amount_usdt_micro, max_activations, activations_count, is_active) VALUES ('${code}', ${bonusMicro}, 10, 0, true) ON CONFLICT (code) DO NOTHING;`,
  ]);
}

// On a paid plan, a cheaper one used to offer "Pay from balance", which
// started it at once and threw away the rest of the paid period. It is now a
// switch scheduled for the end of the period, and a balance too short for the
// renewal is called out up front.
test('on Pro: Basic is scheduled, not bought, and a short balance is flagged', async ({ page }) => {
  const email = uniqueEmail('switch');
  const promoCode = `E2E-SWITCH-${Date.now()}`;
  seedPromoCode(promoCode, 3_000_000); // $3.00

  await registerAndLogin(page, email);

  await page.getByRole('button', { name: /\$0\.00 USDT/ }).click();
  await page.getByRole('button', { name: 'Промокод' }).click();
  await page.getByPlaceholder('PROMOCODE').fill(promoCode);
  await page.getByRole('button', { name: 'Применить' }).click();
  await expect(page.getByRole('button', { name: /\$3\.00 USDT/ })).toBeVisible();
  await page.getByRole('button', { name: '✕' }).click();

  const proCard = page.locator('div.rounded-xl.bg-dark-900:has-text("Pro")').first();
  const basicCard = page.locator('div.rounded-xl.bg-dark-900:has-text("Basic")').first();

  await proCard.getByRole('button', { name: 'Оплатить с баланса' }).click();
  await expect(page.getByText(/(Активна|Active) · PRO/)).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('button', { name: /\$1\.00 USDT/ })).toBeVisible();

  // Current plan: renew, not buy again.
  await expect(proCard.getByRole('button', { name: 'Продлить сейчас' })).toBeVisible();

  // $1 left, Pro renews for $2: warned now, with the cheaper plan that fits.
  const shortfall = page.getByTestId('renewal-shortfall');
  await expect(shortfall).toContainText('спишет $2.00, а на балансе $1.00');
  await expect(shortfall).toContainText('Пополните на $1.00');
  await expect(shortfall).toContainText('«Basic»');

  // The trial would replace the paid plan on the spot: not offered.
  const trialCard = page.locator('div.rounded-xl.bg-dark-900:has-text("Пробный")').first();
  await expect(trialCard.getByRole('button')).toHaveCount(0);
  await expect(trialCard).toContainText('Недоступен на платном тарифе');

  // Cheaper plan: a switch from the end of the period, not a purchase.
  await expect(basicCard.getByRole('button', { name: 'Оплатить с баланса' })).toHaveCount(0);
  await expect(basicCard).toContainText('Сейчас ничего не спишется');
  await basicCard.getByRole('button', { name: /^Перейти с / }).click();

  await expect(basicCard).toContainText('Перейдёте на него');
  await expect(proCard).toContainText('тариф «Basic»');
  await expect(page.getByText(/(Активна|Active) · PRO/)).toBeVisible();
  await expect(page.getByRole('button', { name: /\$1\.00 USDT/ })).toBeVisible();
  // Basic renews for $1, which the balance covers.
  await expect(shortfall).toHaveCount(0);

  // The server refuses the old "buy the cheaper plan now" path outright.
  const token = await page.evaluate(() => localStorage.getItem('vpn_auth_token'));
  const buyBasic = await page.request.post('/api/v1/user/billing/purchase', {
    headers: { Authorization: `Bearer ${token}` },
    data: { tariffId: 'basic', isAnnual: false },
  });
  expect(buyBasic.status()).toBe(400);
  const profile = await (
    await page.request.get('/api/v1/user/profile', { headers: { Authorization: `Bearer ${token}` } })
  ).json();
  expect(profile.balanceUsdtMicro).toBe(1_000_000);
  expect(profile.subscription.tariffId).toBe('pro');
  expect(profile.subscription.nextTariffId).toBe('basic');

  await basicCard.getByRole('button', { name: 'Отменить переход' }).click();
  await expect(basicCard.getByRole('button', { name: /^Перейти с / })).toBeVisible();
  await expect(shortfall).toBeVisible();
});
