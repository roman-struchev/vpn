import { test, expect } from '@playwright/test';
import { registerAndLogin, uniqueEmail } from './adminHelpers';

test.describe('Multi-network crypto deposits E2E', () => {
  test('user generates invoices across TRON and EVM networks, and claims payment', async ({ page }) => {
    const email = uniqueEmail('deposit');
    await registerAndLogin(page, email);

    // Open Top-up modal
    await page.getByRole('button', { name: /\$0\.00 USDT/ }).click();
    await expect(page.getByText('Пополнить баланс')).toBeVisible();

    // 1. TRON network (default)
    await page.getByRole('button', { name: '$5', exact: true }).click();
    await page.getByRole('button', { name: 'Получить адрес для пополнения' }).click();

    await expect(page.getByText('Адрес TRC-20:')).toBeVisible();
    await expect(page.getByText('TXxxDefaultDepositAddressTRC20')).toBeVisible();
    await expect(page.getByText('Отправьте ровно:')).toBeVisible();

    // 2. Switch to BASE (EVM)
    await page.getByRole('button', { name: 'Base' }).click();
    await page.getByRole('button', { name: '$10', exact: true }).click();
    await page.getByRole('button', { name: 'Получить адрес для пополнения' }).click();

    await expect(page.getByText('Адрес BASE (EVM):')).toBeVisible();
    await expect(page.getByText('0x1111111111111111111111111111111111111111')).toBeVisible();

    // 3. Claim transaction using the claim form
    const txHash = `0xclaim_test_${Date.now()}`;
    await page.getByPlaceholder('Хеш транзакции (TxID)').fill(txHash);
    await page.getByPlaceholder('Сумма в USDT').fill('10');
    await page.getByRole('button', { name: 'Зачислить на баланс' }).click();

    // Verify claim status and updated balance in navbar
    await expect(page.getByText(/Зачислено \$10\.00 USDT/)).toBeVisible();
    await expect(page.getByRole('button', { name: /\$10\.00 USDT/ })).toBeVisible();
  });
});
