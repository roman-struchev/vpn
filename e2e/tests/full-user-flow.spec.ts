import { test, expect, type Page } from '@playwright/test';

// One continuous journey through the web client's main features, run against
// the real server + Postgres (not mocked) — deliberately an integration
// check, not a component test: it exercises auth, billing (free-tariff
// activation, invoice generation), device/quota management and session
// handling end to end, the way a real user actually would.
//
// Written as a single test with test.step() sub-steps (not separate test()
// blocks) on purpose: Playwright gives every test() its own isolated
// BrowserContext by default, so a token written to localStorage in one
// test() would not be visible in the next — the whole point here is that
// the *same* browser session carries state across the journey.
//
// Requires: `docker compose up -d postgres` and `./gradlew :server:bootRun`
// already running (see e2e/README.md) — and a web dev server (`npm run dev`
// in web/) unless E2E_BASE_URL points at the server's bundled static build.

const uniqueEmail = () => `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const PASSWORD = 'Test-Passw0rd!';

async function openAuthModal(page: Page) {
  await page.getByRole('button', { name: 'Войти' }).click();
}

test('register -> subscribe -> device -> top-up -> logout -> login', async ({ page }) => {
  const email = uniqueEmail();

  await test.step('anonymous visitor registers a new account', async () => {
    await page.goto('/');

    await openAuthModal(page);
    await page.getByRole('button', { name: 'Нет аккаунта? Зарегистрироваться' }).click();

    await page.getByPlaceholder('you@example.com').fill(email);
    await page.getByPlaceholder('••••••••').fill(PASSWORD);
    await page.getByRole('button', { name: 'Создать аккаунт' }).click();

    // Auth modal closes and the dashboard replaces the landing page once
    // the profile fetch (App.tsx refreshUser) resolves.
    // The subscription banner shows this text twice (badge + h1) when there's
    // no active subscription yet.
    await expect(page.getByText('Нет активной подписки').first()).toBeVisible();
    await expect(page.getByRole('button', { name: /\$0\.00 USDT/ })).toBeVisible();
  });

  await test.step('activates the free trial tariff from the dashboard', async () => {
    // "Активировать бесплатно", not "Оплатить с баланса" — a $0 tariff has
    // nothing to charge the balance for; see docs/ROADMAP_PROGRESS.md.
    const trialCard = page.locator('div.rounded-xl.bg-dark-900:has-text("Пробный")').first();
    await trialCard.getByRole('button', { name: 'Активировать бесплатно' }).click();

    await expect(page.getByText('Трафик:', { exact: false })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('Active · TRIAL', { exact: false })).toBeVisible();
    // NOT a VLESS copy-link button here: docs/PLAN.md §1 deliberately
    // restricts subscription-link export to paid plans
    // (SubscriptionExportService.exportVlessLinks rejects tariff "trial").
  });

  await test.step('a device that registered itself (no manual "add device" UI anymore — see 0a090ff/removal this session) shows up and can be revoked', async () => {
    // Devices only ever appear here by actually connecting from a client
    // (registerOrTouchDevice, native apps) — there's no web UI action left
    // that creates one (the manual "Add Device" form was removed as a dead
    // end: deviceName/platform were purely cosmetic and never wired to any
    // usable vless link). Simulate that real registration the same way a
    // native client would — a direct POST to the still-live server endpoint
    // — so this step can still exercise the list/revoke UI.
    const token = await page.evaluate(() => localStorage.getItem('vpn_auth_token'));
    const addRes = await page.request.post('/api/v1/user/devices', {
      headers: { Authorization: `Bearer ${token}` },
      data: { deviceName: 'E2E Test Phone', platform: 'OTHER' },
    });
    expect(addRes.ok(), await addRes.text()).toBeTruthy();

    await page.reload();
    await expect(page.getByText('E2E Test Phone')).toBeVisible();
    await expect(page.getByText('Active connections: 1')).toBeVisible();

    page.once('dialog', (dialog) => dialog.accept());
    await page.getByTitle('Отозвать доступ').click();

    await expect(page.getByText('E2E Test Phone')).toHaveCount(0);
    await expect(page.getByText('Active connections: 0')).toBeVisible();
  });

  await test.step('generates a TRC-20 deposit invoice', async () => {
    await page.getByRole('button', { name: /USDT/ }).click(); // navbar balance button opens top-up
    await expect(page.getByText('Пополнить баланс (USDT)')).toBeVisible();
    // TRON is the default network in the selector added alongside ERC-20 support.

    await page.getByRole('button', { name: '$10', exact: true }).click();
    await page.getByRole('button', { name: 'Get Deposit Address' }).click();

    await expect(page.getByText('TRC-20 Address:')).toBeVisible();
    await expect(page.getByText('Exact amount to send:')).toBeVisible();

    await page.getByRole('button', { name: '✕' }).click();
  });

  await test.step('logs out and back in, session state persists', async () => {
    await page.getByTitle('Выйти').click();
    await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible();
    // Back on the landing page — the trial tariff card is the marker. Scoped to
    // the card heading because the landing copy also says "Пробный тариф ..." in
    // prose, which a loose getByText would match too.
    await expect(page.getByRole('heading', { name: 'Пробный', exact: true })).toBeVisible();

    await openAuthModal(page);
    // AuthModal is always mounted (isOpen just toggles a null render), so its
    // isRegister state survived from the earlier registration step — force
    // it back to login mode rather than assuming which one it's in.
    const switchToLogin = page.getByRole('button', { name: 'Уже есть аккаунт? Войти' });
    if (await switchToLogin.isVisible().catch(() => false)) {
      await switchToLogin.click();
    }
    await page.getByPlaceholder('you@example.com').fill(email);
    await page.getByPlaceholder('••••••••').fill(PASSWORD);
    // Scoped to the modal's <form> — an exact-name "Войти" match would
    // otherwise also hit the (overlay-hidden but still-in-DOM) navbar
    // trigger button behind the modal and fail as ambiguous.
    await page.locator('form').getByRole('button', { name: 'Войти', exact: true }).click();

    // The trial subscription activated earlier in this run is still there.
    await expect(page.getByText('Active · TRIAL', { exact: false })).toBeVisible();
  });
});
