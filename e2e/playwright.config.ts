import { defineConfig, devices } from '@playwright/test';

// Points at the web dev server (npm run dev in web/, port 3000, proxies /api
// to localhost:8080) by default — fastest local loop. Point E2E_BASE_URL at
// http://localhost:8080 instead to exercise the actual bundled static build
// server/src/main/resources/static (what `copyWebDist` + bootJar ship in
// prod) rather than the Vite dev server.
const baseURL = process.env.E2E_BASE_URL || 'http://localhost:3000';

export default defineConfig({
  testDir: './tests',
  // Deletes the throwaway users every test run registers (see the file for
  // why this is safe/sufficient) so the local Postgres doesn't accumulate
  // one row per run forever.
  globalTeardown: './global-teardown.mjs',
  // The full-flow spec is one continuous user journey (register -> dashboard
  // -> device -> top-up -> logout -> login), so tests run serially, not
  // workers-in-parallel, to keep output easy to follow and avoid two runs
  // racing the same freshly-registered account.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  timeout: 30_000,
  use: {
    baseURL,
    // Headless in CI (CI=true is set by virtually every CI system); headed
    // locally so a human watching this run actually sees the browser.
    headless: !!process.env.CI,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    launchOptions: {
      slowMo: process.env.CI ? 0 : 150,
    },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
