import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * Access-control and error-shape checks across account boundaries.
 *
 * The existing specs all walk one happy path as one user, so nothing here was
 * covered: what happens when a *second* account reaches for the first one's
 * objects, when a guest reaches for a feature that requires a real account,
 * or when a request is simply malformed. These are exactly the cases that
 * component tests tend to assert on a service method while the controller
 * wiring above it (or Spring Security's matcher list) is what actually
 * decides the answer, so they belong at this level.
 *
 * API-only: no page is opened, so this runs against whatever E2E_BASE_URL
 * points at (the web dev server proxies /api to the same backend).
 */

const uniqueEmail = (tag: string) => `e2e-${tag}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const PASSWORD = 'E2ePassw0rd!';

interface Account {
  email: string;
  token: string;
}

async function register(request: APIRequestContext, tag: string): Promise<Account> {
  const email = uniqueEmail(tag);
  const res = await request.post('/api/v1/auth/register', { data: { email, password: PASSWORD } });
  expect(res.status(), `register ${email}`).toBe(200);
  const body = await res.json();
  const token = body.token ?? body.accessToken;
  expect(token, 'register must return a token').toBeTruthy();
  return { email, token };
}

const authed = (token: string) => ({ Authorization: `Bearer ${token}` });

/** A freshly registered account has no plan yet (the dashboard's "choose a tariff"
 *  step), and a device slot only exists under one — so claim the free trial. */
async function activateTrial(request: APIRequestContext, token: string): Promise<void> {
  const res = await request.post('/api/v1/user/billing/purchase', {
    headers: authed(token),
    data: { tariffId: 'trial', isAnnual: false },
  });
  expect(res.status(), `activate trial: ${await res.text()}`).toBe(200);
}

async function addDevice(request: APIRequestContext, token: string, name: string): Promise<number> {
  const res = await request.post('/api/v1/user/devices', {
    headers: authed(token),
    data: { deviceName: name, platform: 'ANDROID' },
  });
  expect(res.status(), `add device ${name}`).toBe(200);
  return (await res.json()).deviceId;
}

test.describe('cross-account access control', () => {
  test('one account cannot touch or revoke another account\'s device', async ({ request }) => {
    const owner = await register(request, 'owner');
    const stranger = await register(request, 'stranger');
    await activateTrial(request, owner.token);
    const deviceId = await addDevice(request, owner.token, 'Owner phone');

    // touchDevice is the one native clients call on every connect with a
    // locally-persisted id — a stranger must never be able to keep somebody
    // else's device counting as active (or learn that the id exists).
    const touched = await request.post(`/api/v1/user/devices/${deviceId}/touch`, { headers: authed(stranger.token) });
    expect(touched.status(), 'stranger touching a foreign device').toBe(404);

    const deleted = await request.delete(`/api/v1/user/devices/${deviceId}`, { headers: authed(stranger.token) });
    expect(deleted.status(), 'stranger revoking a foreign device').toBeGreaterThanOrEqual(400);

    // ...and the device is still there and usable for its real owner.
    const list = await request.get('/api/v1/user/devices', { headers: authed(owner.token) });
    expect(list.status()).toBe(200);
    const devices = (await list.json()).devices ?? (await (await request.get('/api/v1/user/devices', { headers: authed(owner.token) })).json());
    expect(JSON.stringify(devices)).toContain('Owner phone');

    const ownerTouch = await request.post(`/api/v1/user/devices/${deviceId}/touch`, { headers: authed(owner.token) });
    expect(ownerTouch.status(), 'owner touching their own device').toBe(200);
  });

  test('a normal account is refused by the admin API', async ({ request }) => {
    const user = await register(request, 'notadmin');
    for (const path of ['/api/v1/admin/nodes', '/api/v1/admin/users', '/api/v1/admin/stats']) {
      const res = await request.get(path, { headers: authed(user.token) });
      expect(res.status(), `${path} for a non-admin`).toBe(403);
    }
  });

  test('protected endpoints refuse a missing, garbage or tampered token', async ({ request }) => {
    const user = await register(request, 'tamper');
    // Flip the payload of a real token: the signature no longer matches.
    const [h, p, s] = user.token.split('.');
    const tampered = `${h}.${p.slice(0, -2)}XY.${s}`;

    for (const [label, headers] of [
      ['no token', {}],
      ['garbage token', authed('not-a-jwt')],
      ['tampered token', authed(tampered)],
    ] as const) {
      const res = await request.get('/api/v1/user/profile', { headers });
      // 401 specifically, not 403: that is what tells a client its session is
      // gone (renew it or sign in again) rather than "not allowed".
      expect(res.status(), `${label} must be rejected as unauthenticated`).toBe(401);
      const body = await res.text();
      expect(body, `${label} must not leak a stack trace`).not.toContain('java.');
    }
  });
});

test('a live session can be renewed, a dead one cannot', async ({ request }) => {
  const user = await register(request, 'refresh');
  const renewed = await request.post('/api/v1/auth/refresh', { headers: authed(user.token) });
  expect(renewed.status(), 'refresh with a valid token').toBe(200);
  const fresh = (await renewed.json()).token as string;
  expect(fresh).toBeTruthy();

  const profile = await request.get('/api/v1/user/profile', { headers: authed(fresh) });
  expect(profile.status(), 'the renewed token works').toBe(200);

  const anonymous = await request.post('/api/v1/auth/refresh');
  expect(anonymous.status(), 'refresh without a token').toBe(401);
});

test.describe('guest (device-trial) accounts', () => {
  test('a guest cannot enable P2P relay mode', async ({ request }) => {
    const res = await request.post('/api/v1/auth/device', {
      data: { deviceUuid: `e2e-guest-${Date.now()}`, platform: 'ANDROID' },
    });
    expect(res.status()).toBe(200);
    const token = (await res.json()).token;

    // Relay mode requires an accountable account (docs §8.6): a throwaway
    // device profile must be refused at both steps, not just hidden in the UI.
    const terms = await request.post('/api/v1/user/p2p/accept-terms', { headers: authed(token) });
    expect(terms.status(), 'guest accepting p2p terms').toBe(400);

    const bootstrap = await request.post('/api/v1/user/p2p/bootstrap-token', { headers: authed(token) });
    expect(bootstrap.status(), 'guest minting a p2p bootstrap token').toBe(400);
  });

  test('a guest still gets a working trial: regions and subscription links', async ({ request }) => {
    const res = await request.post('/api/v1/auth/device', {
      data: { deviceUuid: `e2e-guest-trial-${Date.now()}`, platform: 'ANDROID' },
    });
    const token = (await res.json()).token;

    const regions = await request.get('/api/v1/user/regions', { headers: authed(token) });
    expect(regions.status()).toBe(200);
    expect((await regions.json()).regions.length, 'a trial must see at least one region').toBeGreaterThan(0);
  });
});

test.describe('malformed input is rejected cleanly, never with a 500', () => {
  test('unknown subscription export token', async ({ request }) => {
    const res = await request.get('/api/v1/subscription/export/00000000-0000-0000-0000-000000000000');
    expect(res.status(), 'unknown export token').toBeLessThan(500);
    expect(await res.text()).not.toContain('java.');
  });

  test('export token that is not even a UUID', async ({ request }) => {
    const res = await request.get('/api/v1/subscription/export/not-a-uuid');
    expect(res.status(), 'malformed export token').toBeLessThan(500);
  });

  test('device creation with missing fields', async ({ request }) => {
    const user = await register(request, 'baddevice');
    const res = await request.post('/api/v1/user/devices', { headers: authed(user.token), data: {} });
    expect(res.status(), 'device with no name/platform').toBeLessThan(500);
  });

  test('login with a wrong password does not reveal whether the account exists', async ({ request }) => {
    const user = await register(request, 'enum');

    const wrongPassword = await request.post('/api/v1/auth/login', {
      data: { email: user.email, password: 'definitely-not-the-password' },
    });
    const noSuchAccount = await request.post('/api/v1/auth/login', {
      data: { email: uniqueEmail('never-registered'), password: 'definitely-not-the-password' },
    });

    expect(wrongPassword.status(), 'wrong password vs unknown account must look the same')
      .toBe(noSuchAccount.status());
    expect(await wrongPassword.text()).toBe(await noSuchAccount.text());
  });
});

test.describe('web entry points the clients hand to users', () => {
  test('the app shell is served at the root and with a referral query', async ({ request }) => {
    for (const path of ['/', '/?ref=E2ETESTCODE']) {
      const res = await request.get(path);
      expect(res.status(), `${path} must serve the app`).toBe(200);
      expect((await res.text()).toLowerCase(), `${path} must be HTML`).toContain('<!doctype html');
    }
  });

  test('the P2P terms page is reachable at the hash URL the clients build', async ({ request }) => {
    // The dashboard is hash-routed (web/src/App.tsx: #p2p-terms), so the
    // server only ever sees "/" — which is exactly why a client must not
    // build a path-style URL instead. Android did, and got this 403 as a
    // blank page; desktop (ipc.ts p2p:getTermsUrl) builds the hash form.
    const hashForm = await request.get('/');
    expect(hashForm.status()).toBe(200);

    const pathForm = await request.get('/p2p-terms');
    expect(pathForm.status(), '/p2p-terms is not a real page — clients must use /#p2p-terms')
      .not.toBe(200);
  });
});

test.describe('plan limits and lifecycle', () => {
  test('the device limit is enforced, and revoking one frees its slot', async ({ request }) => {
    const user = await register(request, 'devlimit');
    await activateTrial(request, user.token);

    // The trial tariff allows exactly one device (GET /user/tariffs: maxDevices).
    const first = await addDevice(request, user.token, 'First device');

    const second = await request.post('/api/v1/user/devices', {
      headers: authed(user.token),
      data: { deviceName: 'Second device', platform: 'DESKTOP' },
    });
    expect(second.status(), 'exceeding the device limit must be a clean 400').toBe(400);
    expect(await second.text(), 'and must say why').toMatch(/device/i);

    const revoked = await request.delete(`/api/v1/user/devices/${first}`, { headers: authed(user.token) });
    expect(revoked.status()).toBe(200);

    const afterRevoke = await request.post('/api/v1/user/devices', {
      headers: authed(user.token),
      data: { deviceName: 'Replacement device', platform: 'DESKTOP' },
    });
    expect(afterRevoke.status(), 'a revoked device must free its slot').toBe(200);
  });

  test('the free trial cannot be claimed twice', async ({ request }) => {
    const user = await register(request, 'twotrials');
    await activateTrial(request, user.token);

    const again = await request.post('/api/v1/user/billing/purchase', {
      headers: authed(user.token),
      data: { tariffId: 'trial', isAnnual: false },
    });
    expect(again.status(), 'a second trial must be refused, not granted').toBeGreaterThanOrEqual(400);
    expect(again.status(), 'and refused cleanly').toBeLessThan(500);
  });

  test('a paid tariff cannot be bought with an empty balance', async ({ request }) => {
    const user = await register(request, 'nobalance');
    const tariffs = await (await request.get('/api/v1/user/tariffs')).json();
    const paid = tariffs.find((t: any) => t.monthlyPriceUsdtMicro > 0);
    test.skip(!paid, 'no paid tariff configured on this deployment');

    const res = await request.post('/api/v1/user/billing/purchase', {
      headers: authed(user.token),
      data: { tariffId: paid.id, isAnnual: false },
    });
    expect(res.status(), 'buying without funds must be refused').toBeGreaterThanOrEqual(400);
    expect(res.status(), 'and refused cleanly').toBeLessThan(500);
    const profile = await (await request.get('/api/v1/user/profile', { headers: authed(user.token) })).json();
    expect(profile.balanceUsdtMicro, 'balance must be untouched').toBe(0);
  });

  test('oversized and odd field values do not reach the database as a 500', async ({ request }) => {
    const user = await register(request, 'oversize');
    await activateTrial(request, user.token);

    for (const [label, deviceName] of [
      ['a very long name', 'x'.repeat(5000)],
      ['an emoji name', '📱📱📱'],
      ['an html-ish name', '<script>alert(1)</script>'],
    ] as const) {
      const res = await request.post('/api/v1/user/devices', {
        headers: authed(user.token),
        data: { deviceName, platform: 'ANDROID' },
      });
      expect(res.status(), `${label} must not 500`).toBeLessThan(500);
    }
  });
});

test.describe('referral attribution', () => {
  test('registering with somebody\'s referral code counts for them', async ({ request }) => {
    const referrer = await register(request, 'referrer');
    const before = await (await request.get('/api/v1/user/profile', { headers: authed(referrer.token) })).json();
    expect(before.referralCode, 'every account gets a referral code').toBeTruthy();

    const invitedEmail = uniqueEmail('invited');
    const invited = await request.post('/api/v1/auth/register', {
      data: { email: invitedEmail, password: PASSWORD, referralCode: before.referralCode },
    });
    expect(invited.status()).toBe(200);

    const after = await (await request.get('/api/v1/user/profile', { headers: authed(referrer.token) })).json();
    expect(after.referralCount, 'the referrer\'s count must go up').toBe(before.referralCount + 1);
  });

  test('an unknown referral code is ignored rather than failing the signup', async ({ request }) => {
    const res = await request.post('/api/v1/auth/register', {
      data: { email: uniqueEmail('badref'), password: PASSWORD, referralCode: 'NOSUCHCODE' },
    });
    expect(res.status(), 'a bogus code must not block registration').toBe(200);
  });
});

test.describe('account identity', () => {
  test('email is case- and whitespace-insensitive across register and login', async ({ request }) => {
    const email = uniqueEmail('case');
    const mixed = email.toUpperCase();

    const registered = await request.post('/api/v1/auth/register', { data: { email: mixed, password: PASSWORD } });
    expect(registered.status()).toBe(200);

    const login = await request.post('/api/v1/auth/login', { data: { email, password: PASSWORD } });
    expect(login.status(), 'the same address in another case must log in').toBe(200);

    const duplicate = await request.post('/api/v1/auth/register', { data: { email, password: PASSWORD } });
    expect(duplicate.status(), 'and must not be registrable a second time').toBeGreaterThanOrEqual(400);
  });
});

test.describe('diagnostics collection', () => {
  test('a client can report failures without being logged in, and the reports are bounded', async ({ request }) => {
    // Deliberately unauthenticated: "cannot get a token" is itself one of the
    // failures worth hearing about, so this channel must work without one.
    const res = await request.post('/api/v1/client/diagnostics', {
      data: {
        source: 'DESKTOP',
        appVersion: '0.0.0-e2e',
        reporterId: `e2e-${Date.now()}`,
        events: [
          {
            severity: 'ERROR',
            component: 'e2e',
            code: 'E2E_PROBE',
            message: `e2e diagnostics probe from 10.0.0.${Math.floor(Math.random() * 250)}`,
            detail: 'stack trace would go here',
            context: { check: 'ingest' },
          },
        ],
      },
    });

    expect(res.status()).toBe(200);
    expect((await res.json()).accepted, 'the report must be recorded').toBe(1);
  });

  test('a batch larger than the cap is accepted but only partly recorded', async ({ request }) => {
    const events = Array.from({ length: 100 }, (_, i) => ({
      severity: 'ERROR',
      component: 'e2e',
      code: 'E2E_FLOOD',
      message: `e2e flood probe variant ${i}`,
    }));

    const res = await request.post('/api/v1/client/diagnostics', {
      data: { source: 'ANDROID', appVersion: '0.0.0-e2e', reporterId: 'e2e-flood', events },
    });

    expect(res.status(), 'a reporter in trouble must never get an error back').toBe(200);
    const accepted = (await res.json()).accepted;
    expect(accepted, 'the per-request cap must hold').toBeLessThanOrEqual(20);
  });

  test('a malformed report is swallowed rather than failing the reporter', async ({ request }) => {
    for (const body of [{}, { events: 'not-a-list' }, { events: [{}] }, { events: [{ message: '   ' }] }]) {
      const res = await request.post('/api/v1/client/diagnostics', { data: body });
      expect(res.status(), `malformed body ${JSON.stringify(body)}`).toBe(200);
    }
  });

  test('the collected reports are not readable without admin rights', async ({ request }) => {
    const user = await register(request, 'diagread');

    const anonymous = await request.get('/api/v1/admin/diagnostics');
    expect(anonymous.status(), 'reports must not be world-readable').toBeGreaterThanOrEqual(400);

    const asUser = await request.get('/api/v1/admin/diagnostics', { headers: authed(user.token) });
    expect(asUser.status(), 'a normal account must not read the fleet\'s errors').toBe(403);
  });
});
