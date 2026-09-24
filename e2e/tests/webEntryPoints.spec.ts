import { test, expect, type APIRequestContext, type Browser, type Page } from '@playwright/test';
import { promoteToAdmin, PASSWORD } from './adminHelpers';

// Every web URL something outside the site sends people to, opened every way
// it can arrive. The site is hash-routed (web/src/App.tsx), and a page picked
// from the URL once at load is exactly what broke next=/#privacy through the
// handoff — so each destination is checked:
//   - opened directly, signed out and signed in;
//   - through the client -> web handoff (?handoff_code=&next=), which swaps
//     the URL with replaceState after the page has already loaded;
//   - through a handoff whose code is no longer valid;
//   - by an in-page hash change, the way the footer links and back button work.
// Where the links come from:
//   desktop  ProfilePage openWeb('/#privacy' | '/#terms'), openWebHandoff('/#tariffs'),
//            ipc p2p:getTermsUrl  -> <origin>/#p2p-terms
//   android  ProfileFragment /#privacy, /#terms (plain), WebHandoffLauncher /#tariffs,
//            P2pRelaySettingsActivity -> /#p2p-terms
//   server   TrafficNotifier -> /#tariffs (Telegram), UserController -> /?ref=CODE

const uniqueEmail = (tag: string) => `e2e-entry-${tag}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

async function register(request: APIRequestContext, tag: string) {
  const email = uniqueEmail(tag);
  const res = await request.post('/api/v1/auth/register', { data: { email, password: PASSWORD } });
  expect(res.status(), `register ${email}`).toBe(200);
  const body = await res.json();
  return { email, token: (body.token ?? body.accessToken) as string };
}

async function mintHandoff(request: APIRequestContext, token: string): Promise<string> {
  const res = await request.post('/api/v1/auth/web-handoff', { headers: { Authorization: `Bearer ${token}` } });
  expect(res.ok(), await res.text()).toBeTruthy();
  return (await res.json()).code;
}

/** A page in its own browser context, optionally already holding a session. */
async function freshPage(browser: Browser, token?: string): Promise<Page> {
  const context = await browser.newContext();
  if (token) {
    await context.addInitScript((t) => localStorage.setItem('vpn_auth_token', t), token);
  }
  return context.newPage();
}

const DASHBOARD = 'Нет активной подписки';

type Destination = { hash: string; expect: (page: Page) => Promise<void> };

const heading = (name: string): Destination['expect'] => async (page) => {
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible({ timeout: 10_000 });
};

const DOC_PAGES: Destination[] = [
  { hash: '#privacy', expect: heading('Политика конфиденциальности') },
  { hash: '#terms', expect: heading('Условия использования') },
  { hash: '#p2p-terms', expect: heading('Условия и риски режима ретрансляции (P2P)') },
];

test.describe('document pages open from every entry point', () => {
  for (const d of DOC_PAGES) {
    test(`${d.hash}: signed out, signed in, via handoff, via a dead handoff code, via an in-page link`, async ({ browser, request }) => {
      const user = await register(request, d.hash.slice(1));

      await test.step('opened directly, signed out', async () => {
        const page = await freshPage(browser);
        await page.goto('/' + d.hash);
        await d.expect(page);
        await page.context().close();
      });

      await test.step('opened directly, signed in', async () => {
        const page = await freshPage(browser, user.token);
        await page.goto('/' + d.hash);
        await d.expect(page);
        await page.context().close();
      });

      await test.step('through the handoff', async () => {
        const code = await mintHandoff(request, user.token);
        const page = await freshPage(browser);
        await page.goto(`/?handoff_code=${code}&next=${encodeURIComponent('/' + d.hash)}`);
        await d.expect(page);
        expect(new URL(page.url()).hash).toBe(d.hash);
        expect(page.url()).not.toContain('handoff_code');
        await page.context().close();
      });

      await test.step('through a handoff whose code is already used up', async () => {
        const page = await freshPage(browser);
        await page.goto(`/?handoff_code=no-such-code&next=${encodeURIComponent('/' + d.hash)}`);
        await d.expect(page);
        await page.context().close();
      });

      await test.step('by an in-page hash change from the dashboard, and back again', async () => {
        const page = await freshPage(browser, user.token);
        await page.goto('/');
        await expect(page.getByText(DASHBOARD).first()).toBeVisible();
        await page.evaluate((h) => (window.location.hash = h), d.hash);
        await d.expect(page);
        await page.goBack();
        await expect(page.getByText(DASHBOARD).first()).toBeVisible();
        await page.context().close();
      });
    });
  }

  test('the page\'s own "Back" button returns to where the user was', async ({ browser, request }) => {
    const user = await register(request, 'back');
    const code = await mintHandoff(request, user.token);
    const page = await freshPage(browser);
    await page.goto(`/?handoff_code=${code}&next=${encodeURIComponent('/#privacy')}`);
    await heading('Политика конфиденциальности')(page);
    await page.getByRole('button', { name: 'Назад' }).click();
    await expect(page.getByText(DASHBOARD).first()).toBeVisible();
    expect(new URL(page.url()).hash).toBe('');
    await page.context().close();
  });
});

test.describe('"take me to the plans" (#tariffs) lands on the plans', () => {
  // Scrolling is smooth, so give it a moment rather than checking once.
  const plansInView = async (page: Page, locator: ReturnType<Page['locator']>) => {
    await expect(locator).toBeInViewport({ timeout: 10_000 });
  };

  test('signed in, directly and via the handoff (the apps\' "Change plan")', async ({ browser, request }) => {
    const user = await register(request, 'tariffs');

    const direct = await freshPage(browser, user.token);
    await direct.setViewportSize({ width: 1280, height: 700 });
    await direct.goto('/#tariffs');
    await plansInView(direct, direct.locator('#tariffs'));
    await direct.context().close();

    const code = await mintHandoff(request, user.token);
    const viaHandoff = await freshPage(browser);
    await viaHandoff.setViewportSize({ width: 1280, height: 700 });
    await viaHandoff.goto(`/?handoff_code=${code}&next=${encodeURIComponent('/#tariffs')}`);
    await plansInView(viaHandoff, viaHandoff.locator('#tariffs'));
    await viaHandoff.context().close();
  });

  test('signed out — the "traffic is running out" Telegram link, or a dead handoff code', async ({ browser }) => {
    for (const url of ['/#tariffs', `/?handoff_code=no-such-code&next=${encodeURIComponent('/#tariffs')}`]) {
      const page = await freshPage(browser);
      await page.setViewportSize({ width: 1280, height: 700 });
      await page.goto(url);
      await plansInView(page, page.locator('#pricing'));
      await page.context().close();
    }
  });

  test('signed in, following a #tariffs link without reloading the page', async ({ browser, request }) => {
    const user = await register(request, 'tariffs-inpage');
    const page = await freshPage(browser, user.token);
    await page.setViewportSize({ width: 1280, height: 700 });
    await page.goto('/');
    await expect(page.getByText(DASHBOARD).first()).toBeVisible();
    await page.evaluate(() => (window.location.hash = 'tariffs'));
    await plansInView(page, page.locator('#tariffs'));
    await page.context().close();
  });
});

test.describe('the admin panel', () => {
  test('opens via #admin directly and via the handoff, and a non-admin never sees it', async ({ browser, request }) => {
    const admin = await register(request, 'admin');
    promoteToAdmin(admin.email);
    const login = await request.post('/api/v1/auth/login', { data: { email: admin.email, password: PASSWORD } });
    const adminToken = (await login.json()).token as string;

    const direct = await freshPage(browser, adminToken);
    await direct.goto('/#admin');
    await heading('Админ-панель')(direct);
    await direct.context().close();

    const code = await mintHandoff(request, adminToken);
    const viaHandoff = await freshPage(browser);
    await viaHandoff.goto(`/?handoff_code=${code}&next=${encodeURIComponent('/#admin')}`);
    await heading('Админ-панель')(viaHandoff);
    await viaHandoff.context().close();

    const user = await register(request, 'not-admin');
    const plain = await freshPage(browser, user.token);
    await plain.goto('/#admin');
    await expect(plain.getByText(DASHBOARD).first()).toBeVisible();
    await expect(plain.getByRole('heading', { name: 'Админ-панель' })).toHaveCount(0);
    await plain.context().close();
  });
});

test.describe('a referral link', () => {
  test('opens sign-up with the code filled in, also when it arrives with a hash', async ({ browser, request }) => {
    const referrer = await register(request, 'referrer');
    const profile = await request.get('/api/v1/user/profile', { headers: { Authorization: `Bearer ${referrer.token}` } });
    const { referralCode, referralLink } = await profile.json();
    expect(new URL(referralLink).search).toBe(`?ref=${referralCode}`);

    for (const url of [`/?ref=${referralCode}`, `/?ref=${referralCode}#tariffs`]) {
      const page = await freshPage(browser);
      await page.goto(url);
      await expect(page.getByRole('button', { name: 'Создать аккаунт' })).toBeVisible({ timeout: 10_000 });
      await expect(page.locator(`input[value="${referralCode}"]`)).toBeVisible();
      await page.context().close();
    }
  });
});
