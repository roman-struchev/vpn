import { expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';

// Shared by admin.spec.ts and nodes.spec.ts — no ADMIN user is seeded (see
// root README.md "Первый администратор"), so every admin-flow test promotes
// a freshly-registered user directly in Postgres via the same docker exec
// pattern the README documents for manual use.
export function promoteToAdmin(email: string) {
  execFileSync('docker', [
    'exec',
    'vpn-postgres',
    'psql',
    '-U',
    'vpn_user',
    '-d',
    'vpn_db',
    '-c',
    `UPDATE users SET role='ADMIN' WHERE email='${email}';`,
  ]);
}

export const uniqueEmail = (label: string) =>
  `e2e-admin-${label}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

export const PASSWORD = 'Test-Passw0rd!';

export async function registerAndLogin(page: Page, email: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'Войти' }).click();
  await page.getByRole('button', { name: 'Нет аккаунта? Зарегистрироваться' }).click();
  await page.getByPlaceholder('you@example.com').fill(email);
  await page.getByPlaceholder('••••••••').fill(PASSWORD);
  await page.getByRole('button', { name: 'Создать аккаунт' }).click();
  await expect(page.getByText('Нет активной подписки').first()).toBeVisible();
}

/** Re-login as an already-registered user — e.g. after promoting them to
 * ADMIN in the DB, to pick up ROLE_ADMIN in a freshly-issued JWT. */
export async function loginExistingUser(page: Page, email: string) {
  await page.getByRole('button', { name: 'Войти' }).click();
  // AuthModal is always mounted (isOpen just toggles a null render), so its
  // isRegister state survived from the earlier registration step — force it
  // back to login mode rather than assuming which one it's in.
  const switchToLogin = page.getByRole('button', { name: 'Уже есть аккаунт? Войти' });
  if (await switchToLogin.isVisible().catch(() => false)) {
    await switchToLogin.click();
  }
  await page.getByPlaceholder('you@example.com').fill(email);
  await page.getByPlaceholder('••••••••').fill(PASSWORD);
  await page.locator('form').getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByText('Нет активной подписки').first()).toBeVisible();
}

/** Registers a fresh user, promotes them to ADMIN, and logs back in so the
 * JWT actually carries ROLE_ADMIN — the combined flow every admin-panel test
 * needs before it can do anything. Returns the admin's email. */
export async function setUpAdmin(page: Page, label: string): Promise<string> {
  const email = uniqueEmail(label);
  await registerAndLogin(page, email);
  promoteToAdmin(email);
  await page.getByTitle('Выйти').click();
  await loginExistingUser(page, email);
  await page.getByTestId('nav-admin-link').click();
  return email;
}
