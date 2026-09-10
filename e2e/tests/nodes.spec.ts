import { test, expect, type Page } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { setUpAdmin } from './adminHelpers';

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

const AGENT_DIR = path.resolve(__dirname, '../../agent');

interface LocalAgentHandle {
  proc: ChildProcess;
  hostname: string;
  stateFile: string;
}

/** Spawns a real agent/src/index.ts process registering against the local
 * server (localhost:9090) with the given bootstrap token. Each call gets its
 * own hostname and state file so parallel agents (and repeat test runs)
 * never collide with each other or with a developer's own locally-running
 * agent (see agent/src/config.ts — NODE_HOSTNAME/AGENT_STATE_PATH). */
function startLocalAgent(bootstrapToken: string, label: string): LocalAgentHandle {
  const hostname = `e2e-${label}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const stateFile = path.join(os.tmpdir(), `${hostname}.agent-state.json`);

  const proc = spawn('npx', ['tsx', 'src/index.ts'], {
    cwd: AGENT_DIR,
    env: {
      ...process.env,
      BOOTSTRAP_TOKEN: bootstrapToken,
      SERVER_GRPC_URL: 'localhost:9090',
      PUBLIC_IP: '127.0.0.1',
      REGION: 'e2e-test',
      NODE_HOSTNAME: hostname,
      AGENT_STATE_PATH: stateFile,
    },
    stdio: 'pipe',
  });

  // Surfaced only on test failure (Playwright captures stdout/stderr into the
  // test's attachments) — otherwise silent so a passing run stays readable.
  let log = '';
  proc.stdout?.on('data', (d) => (log += d.toString()));
  proc.stderr?.on('data', (d) => (log += d.toString()));
  proc.on('exit', (code) => {
    if (code !== null && code !== 0) console.warn(`[agent:${hostname}] exited ${code}\n${log}`);
  });

  return { proc, hostname, stateFile };
}

function stopLocalAgent(handle: LocalAgentHandle) {
  handle.proc.kill('SIGTERM');
  try {
    fs.unlinkSync(handle.stateFile);
  } catch {
    // already gone, fine
  }
}

async function createBootstrapToken(page: Page, pool: string, type: string): Promise<string> {
  await page.getByRole('button', { name: 'Создать bootstrap-токен' }).click();
  await page.getByTestId('bootstrap-pool-select').selectOption(pool);
  await page.getByTestId('bootstrap-type-select').selectOption(type);
  await page.getByRole('button', { name: 'Создать', exact: true }).click();
  const token = await page.getByTestId('bootstrap-token-value').textContent();
  if (!token) throw new Error('Bootstrap token dialog did not render a token');
  await page.getByRole('button', { name: '✕' }).click();
  return token.trim();
}

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
});
