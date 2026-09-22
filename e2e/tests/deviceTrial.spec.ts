import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { setUpAdmin } from './adminHelpers';

// No-signup device-trial login (POST /api/v1/auth/device,
// DeviceAuthService#authenticateDevice) — a fresh client install gets a
// working trial account with zero user action: no email, no password, no
// registration form, keyed only by a locally-generated device UUID.
//
// The idempotency assertion below (same deviceUuid -> same userId, not a new
// account each call) targets the same contract a concurrency bug was found
// and fixed for server-side this session — a *concurrent*-request version of
// that bug is out of scope for Playwright (see the server-side unit test for
// that), but the basic "calling twice behaves" contract belongs here too.
//
// device_<uuid>@device.local rows created by this spec are cleaned up by
// global-teardown.mjs alongside the usual e2e-*@example.com users.

test.describe('No-signup device-trial login', () => {
  test('same deviceUuid is idempotent, and grants an active trial visible in the UI and admin panel', async ({ page }) => {
    const deviceUuid = randomUUID();
    const deviceEmail = `device_${deviceUuid}@device.local`;
    let userId: number;
    let token: string;

    await test.step('first call with a fresh deviceUuid creates a new trial account', async () => {
      const res = await page.request.post('/api/v1/auth/device', {
        data: { deviceUuid },
      });
      expect(res.ok(), await res.text()).toBeTruthy();

      const body = await res.json();
      expect(body.token).toBeTruthy();
      expect(body.userId).toBeTruthy();
      expect(body.referralCode).toBeTruthy();

      userId = body.userId;
      token = body.token;
    });

    await test.step('second call with the SAME deviceUuid returns the same account, not a duplicate', async () => {
      const res = await page.request.post('/api/v1/auth/device', {
        data: { deviceUuid },
      });
      expect(res.ok(), await res.text()).toBeTruthy();

      const body = await res.json();
      expect(body.userId).toBe(userId);
    });

    await test.step('logging into the web app with the device token shows an already-active TRIAL subscription', async () => {
      await page.goto('/');
      await page.evaluate((t) => localStorage.setItem('vpn_auth_token', t), token);
      await page.reload();

      // No "Активировать бесплатно" click anywhere here — the whole point of
      // this flow is that the trial is already active on first login.
      await expect(page.getByText(/(Активна|Active) · TRIAL/)).toBeVisible({ timeout: 10_000 });
    });

    await test.step('the account shows up in the admin Users tab with the "no signup" badge', async () => {
      const adminPage = await page.context().browser()!.newPage();
      await setUpAdmin(adminPage, 'device-trial');
      await adminPage.getByTestId('admin-tab-users').click();

      await adminPage.getByPlaceholder('Поиск по email...').fill(deviceEmail);
      await expect(adminPage.getByText(deviceEmail)).toBeVisible({ timeout: 10_000 });
      // UsersSection.tsx's isTrialDeviceUser badge (deviceUuid set, no
      // telegramId) — admin UI defaults to ru, so it's the ru copy
      // ("Trial · no signup" in en, adminI18n.ts).
      await expect(adminPage.getByText('Пробный · без регистрации')).toBeVisible();

      await adminPage.close();
    });
  });
});
