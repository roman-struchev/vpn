import { test, expect } from '@playwright/test';
import { setUpAdmin } from './adminHelpers';
import { startLocalAgent, stopLocalAgent, createBootstrapToken } from './agentHelpers';

// Requested: "в интеграционных тестах также используй и локально поднятые
// ноды разных типов" — a real agent/ process (agent/src/index.ts, run via
// `tsx`, no build step) registered against the real local server exactly
// like scripts/install-node.sh's target would, not a mocked node row.
//
// agent/ doesn't need a real xray-core binary to be useful here: it
// degrades to "Running in simulated mode" (see XraySupervisor) when the
// `xray` binary isn't on PATH, but still completes real gRPC registration,
// ConfigSync/ConfigAck, and heartbeats — everything this test needs to
// verify. That's exactly what running the agent locally without a real
// xray install looks like (confirmed live in this project's own dev logs).
//
// For a test that also verifies actual tunneled traffic through a *real*
// xray-core on both node and client sides, see tunnel.spec.ts.

test.describe('Local node agent integration', () => {
  test.setTimeout(90_000);

  test('direct and CDN-type local agents register and appear ONLINE in the admin panel', async ({ page }) => {
    await setUpAdmin(page, 'nodes');
    await page.getByTestId('admin-tab-nodes').click();

    const directToken = await createBootstrapToken(page, 'paid', 'direct');
    const cdnToken = await createBootstrapToken(page, 'paid', 'cdn');

    const direct = startLocalAgent(directToken, 'direct');
    const cdn = startLocalAgent(cdnToken, 'cdn');

    try {
      // Real registration + first heartbeat over a real gRPC stream — not
      // instant. sendHeartbeat() fires immediately on connect (see
      // agent/src/client/grpc-client.ts), so this is network/startup time,
      // not a fixed interval wait.
      for (const { hostname } of [direct, cdn]) {
        await expect(async () => {
          await page.reload();
          await page.getByTestId('admin-tab-nodes').click();
          await expect(page.getByText(hostname)).toBeVisible();
        }).toPass({ timeout: 45_000, intervals: [2000] });
      }

      const directRow = page.locator('tr', { hasText: direct.hostname });
      await expect(directRow.locator('.p-dropdown-label', { hasText: 'ONLINE' })).toBeVisible({ timeout: 15_000 });
      await expect(directRow).toContainText('direct');

      const cdnRow = page.locator('tr', { hasText: cdn.hostname });
      await expect(cdnRow.locator('.p-dropdown-label', { hasText: 'ONLINE' })).toBeVisible({ timeout: 15_000 });
      await expect(cdnRow).toContainText('cdn');
    } finally {
      stopLocalAgent(direct);
      stopLocalAgent(cdn);
    }
  });

  // Admin panel's "Restart Xray" button (NodesSection.tsx) hits
  // POST /nodes/{id}/command?type=COMMAND_TYPE_RESTART_XRAY (AdminController),
  // which is relayed to the node over the same gRPC stream used for
  // ConfigSync/heartbeats. Prove it actually reaches a running agent process
  // — not just that the server accepted the HTTP call — by watching the real
  // agent's own log for the line it prints on receipt (see
  // agent/src/client/grpc-client.ts: `Received server command: ...`).
  test('"Restart Xray" button reaches the running local agent over gRPC', async ({ page }) => {
    await setUpAdmin(page, 'nodes-restart');
    await page.getByTestId('admin-tab-nodes').click();

    const token = await createBootstrapToken(page, 'paid', 'direct');
    const agent = startLocalAgent(token, 'restart');

    try {
      await expect(async () => {
        await page.reload();
        await page.getByTestId('admin-tab-nodes').click();
        await expect(page.getByText(agent.hostname)).toBeVisible();
      }).toPass({ timeout: 45_000, intervals: [2000] });

      const row = page.locator('tr', { hasText: agent.hostname });
      await expect(row.locator('.p-dropdown-label', { hasText: 'ONLINE' })).toBeVisible({ timeout: 15_000 });

      expect(agent.log()).not.toMatch(/Received server command/);

      page.once('dialog', (dialog) => dialog.accept());
      await row.getByTestId(/node-restart-xray-\d+/).click();

      await expect(async () => {
        expect(agent.log()).toMatch(/Received server command: COMMAND_TYPE_RESTART_XRAY/);
      }).toPass({ timeout: 15_000, intervals: [1000] });
    } finally {
      stopLocalAgent(agent);
    }
  });
});
