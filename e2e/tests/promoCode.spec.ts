import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { registerAndLogin, uniqueEmail } from './adminHelpers';

const PROMO_CODE = `E2EPROMO-${Date.now()}`;

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

test.describe('Promo code redemption flow', () => {
  test('a user applies a promo code, balance credits instantly, repeat use is rejected', async ({ page }) => {
    const email = uniqueEmail('promo');
    seedPromoCode(PROMO_CODE, 5_000_000); // 5.00 USDT

    await registerAndLogin(page, email);

    // Initial balance is $0.00
    const balanceBtn = page.getByRole('button', { name: /\$0\.00 USDT/ });
    await expect(balanceBtn).toBeVisible();

    // Open Top-up modal
    await balanceBtn.click();
    await expect(page.getByText('Пополнить баланс')).toBeVisible();

    // Switch to Promo Code tab
    await page.getByRole('button', { name: 'Промокод' }).click();

    // Fill in promo code and activate
    const promoInput = page.getByPlaceholder('PROMOCODE');
    await expect(promoInput).toBeVisible();
    await promoInput.fill(PROMO_CODE);

    await page.getByRole('button', { name: 'Применить' }).click();

    // Verify success banner and updated balance in modal / navbar
    await expect(page.getByText(/Промокод активирован!/)).toBeVisible();
    await expect(page.getByRole('button', { name: /\$5\.00 USDT/ })).toBeVisible();

    // Trying to reapply the same code should be rejected
    await promoInput.fill(PROMO_CODE);
    await page.getByRole('button', { name: 'Применить' }).click();
    await expect(page.getByText(/already used this promo code/i)).toBeVisible();
  });
});

