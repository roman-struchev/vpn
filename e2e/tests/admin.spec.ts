import { test, expect } from '@playwright/test';
import { promoteToAdmin, registerAndLogin, loginExistingUser, uniqueEmail } from './adminHelpers';

// Admin panel (web/src/admin/) — item #1 from docs/ROADMAP_PROGRESS.md §6
// "Возможные дальнейшие улучшения": previously there was no web UI at all,
// only the raw REST API (server/.../AdminController.java, curl-only).
//
// No ADMIN user is seeded (see README.md "Первый администратор") — promote
// a freshly-registered user directly in Postgres via the same docker exec
// pattern the README documents for manual use. See adminHelpers.ts (shared
// with nodes.spec.ts) for promoteToAdmin/registerAndLogin/loginExistingUser.

test.describe('Admin panel', () => {
  test('a non-admin user does not see the admin entry point', async ({ page }) => {
    const email = uniqueEmail('plain');
    await registerAndLogin(page, email);

    await expect(page.getByTestId('nav-admin-link')).toHaveCount(0);
  });

  test('an ADMIN user can reach the panel and see real users/nodes data', async ({ page }) => {
    const email = uniqueEmail('root');
    await registerAndLogin(page, email);
    promoteToAdmin(email);

    // The role is baked into the already-issued JWT, not re-checked live —
    // log out and back in so a fresh JWT picks up ROLE_ADMIN.
    await page.getByTitle('Выйти').click();
    await loginExistingUser(page, email);

    await page.getByTestId('nav-admin-link').click();
    await expect(page.getByTestId('admin-panel')).toBeVisible();

    // Dashboard tab (default) — real aggregate counts, not fixtures.
    await expect(page.getByText('Пользователей')).toBeVisible();

    // Users tab — the account we just created for this test must appear.
    await page.getByTestId('admin-tab-users').click();
    await expect(page.getByText(email)).toBeVisible({ timeout: 10_000 });

    // Nodes tab — renders without error even with zero nodes registered.
    await page.getByTestId('admin-tab-nodes').click();
    await expect(page.getByTestId('admin-panel')).toBeVisible();
  });

  test('admin can credit a user balance and see it reflected', async ({ page }) => {
    const adminEmail = uniqueEmail('root2');
    const targetEmail = uniqueEmail('target');

    // Create the target user in one throwaway context so its $0.00 balance
    // isn't confused with the admin's own.
    const targetPage = await page.context().browser()!.newPage();
    await registerAndLogin(targetPage, targetEmail);
    await targetPage.close();

    await registerAndLogin(page, adminEmail);
    promoteToAdmin(adminEmail);
    await page.getByTitle('Выйти').click();
    await loginExistingUser(page, adminEmail);

    await page.getByTestId('nav-admin-link').click();
    await page.getByTestId('admin-tab-users').click();

    await page.getByPlaceholder('Поиск по email...').fill(targetEmail);
    await expect(page.getByText(targetEmail)).toBeVisible({ timeout: 10_000 });
    await page.getByText(targetEmail).click();

    await page.getByPlaceholder('Сумма (USDT, может быть отрицательной)').fill('7.5');
    await page.getByPlaceholder('Причина').fill('e2e test credit');
    // Two "Применить" buttons exist in the dialog (balance + subscription
    // extend) — the balance one comes first in DOM order.
    await page.getByRole('button', { name: 'Применить', exact: true }).first().click();

    // Dialog closes and the table reloads with the updated balance.
    await expect(page.getByText('$7.50')).toBeVisible({ timeout: 10_000 });
  });
});
