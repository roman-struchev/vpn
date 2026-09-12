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

test.describe('Tariff upgrade and limits E2E', () => {
  test('user on trial upgrades to Pro plan; limits update immediately', async ({ page }) => {
    const email = uniqueEmail('upgrade');
    const promoCode = `E2E-UPGRADE-${Date.now()}`;
    seedPromoCode(promoCode, 5_000_000); // $5.00 USDT

    await registerAndLogin(page, email);

    // 1. Activate free trial
    const trialCard = page.locator('div.rounded-xl.bg-dark-900:has-text("Пробный")').first();
    await trialCard.getByRole('button', { name: 'Активировать бесплатно' }).click();

    await expect(page.getByText('Active · TRIAL', { exact: false })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('heading', { name: /Трафик:.*1 GB/ })).toBeVisible();

    // Register 1st device
    const token = await page.evaluate(() => localStorage.getItem('vpn_auth_token'));
    const dev1Res = await page.request.post('/api/v1/user/devices', {
      headers: { Authorization: `Bearer ${token}` },
      data: { deviceName: 'Trial Phone', platform: 'ANDROID' },
    });
    expect(dev1Res.ok()).toBeTruthy();

    // Attempting to register 2nd device on trial must fail (1 device limit)
    const dev2Res = await page.request.post('/api/v1/user/devices', {
      headers: { Authorization: `Bearer ${token}` },
      data: { deviceName: 'Trial Laptop', platform: 'DESKTOP' },
    });
    expect(dev2Res.status()).toBe(400);
    const errText = await dev2Res.text();
    expect(errText).toContain('Device limit exceeded');

    // 2. Top-up balance using promo code
    await page.getByRole('button', { name: /\$0\.00 USDT/ }).click();
    await page.getByRole('button', { name: 'Промокод' }).click();
    const promoInput = page.getByPlaceholder('PROMOCODE');
    await promoInput.fill(promoCode);
    await page.getByRole('button', { name: 'Применить' }).click();

    await expect(page.getByText(/Промокод активирован!/)).toBeVisible();
    await expect(page.getByRole('button', { name: /\$5\.00 USDT/ })).toBeVisible();

    // Close top-up modal
    await page.getByRole('button', { name: '✕' }).click();

    // 3. Upgrade to Pro tariff ($2.00/month, 100 GB, 5 devices)
    const proCard = page.locator('div.rounded-xl.bg-dark-900:has-text("Pro")').first();
    await proCard.getByRole('button', { name: 'Оплатить с баланса' }).click();

    // 4. Verify that Pro plan takes effect immediately
    await expect(page.getByText('Active · PRO', { exact: false })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('heading', { name: /Трафик:.*100 GB/ })).toBeVisible();

    // Balance debited by $2.00 (from $5.00 to $3.00)
    await expect(page.getByRole('button', { name: /\$3\.00 USDT/ })).toBeVisible();

    // 5. Verify device limit is now 5: 2nd device can now be registered successfully
    const dev2Retry = await page.request.post('/api/v1/user/devices', {
      headers: { Authorization: `Bearer ${token}` },
      data: { deviceName: 'Pro Laptop', platform: 'DESKTOP' },
    });
    expect(dev2Retry.ok()).toBeTruthy();

    // Refresh and check devices list
    await page.reload();
    await expect(page.getByText('Trial Phone')).toBeVisible();
    await expect(page.getByText('Pro Laptop')).toBeVisible();
    await expect(page.getByTestId('device-count')).toHaveText(/^2 \/ 5/);
  });
});
