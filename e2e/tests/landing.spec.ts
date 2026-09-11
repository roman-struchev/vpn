import { test, expect } from '@playwright/test';

// Regression guard for a real bug found while manually testing this flow:
// GET /api/v1/user/tariffs wasn't in SecurityConfig's permitAll list, so the
// landing page's pricing section silently rendered empty for every
// anonymous visitor (App.tsx fetches tariffs unconditionally on load,
// before any login). Fixed in SecurityConfig — this test keeps it fixed.
test.describe('Landing page (anonymous visitor, no login)', () => {
  test('shows the public tariff list without authenticating', async ({ page }) => {
    await page.goto('/');

    // Every plan is asserted through its tariff-card heading, not a loose
    // getByText: the landing copy legitimately contains these words in prose
    // too ("Пробный тариф не требует карты", "...Pro"), so a substring match
    // resolves to several elements and fails on strict mode instead of telling
    // you whether the pricing section actually rendered.
    await expect(page.getByRole('heading', { name: 'Пробный', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Basic', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Pro', exact: true })).toBeVisible();
  });

  test('language switch toggles UI text between ru and en', async ({ page }) => {
    await page.goto('/');

    await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible();

    await page.getByRole('button', { name: 'ru', exact: true }).click();

    await expect(page.getByRole('button', { name: 'Log In' })).toBeVisible();
  });
});
