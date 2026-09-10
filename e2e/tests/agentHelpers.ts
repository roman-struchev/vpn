import { type Page } from '@playwright/test';
import { spawn, execFileSync, type ChildProcess } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';

// Shared by nodes.spec.ts and tunnel.spec.ts.

export const AGENT_DIR = path.resolve(__dirname, '../../agent');

export interface LocalAgentHandle {
  proc: ChildProcess;
  hostname: string;
  stateFile: string;
  xrayConfigFile: string;
  log: () => string;
}

/** Spawns a real agent/src/index.ts process registering against the local
 * server (localhost:9090) with the given bootstrap token. Each call gets its
 * own hostname and state file so parallel agents (and repeat test runs)
 * never collide with each other or with a developer's own locally-running
 * agent (see agent/src/config.ts — NODE_HOSTNAME/AGENT_STATE_PATH).
 *
 * Pass `xrayBinPath` (see resolveXrayBinaryPath below) to run the agent
 * against a *real* xray-core binary instead of XraySupervisor's "simulated
 * mode" fallback — required for tunnel.spec.ts, where actual traffic has to
 * flow through a real REALITY inbound. */
export function startLocalAgent(
  bootstrapToken: string,
  label: string,
  xrayBinPath?: string
): LocalAgentHandle {
  const hostname = `e2e-${label}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const stateFile = path.join(os.tmpdir(), `${hostname}.agent-state.json`);
  // agent/src/config.ts defaults this to a fixed path relative to cwd
  // (agent/xray-config.json) — shared, uncontended, by every agent process
  // that doesn't override it. Fine for nodes.spec.ts's simulated-mode agents
  // (nothing ever reads the file for real), but two agents racing to
  // write/read the *same* file while a real xray-core process is actually
  // consuming it (tunnel.spec.ts) is a genuine, if rare, corruption risk.
  const xrayConfigFile = path.join(os.tmpdir(), `${hostname}.xray-config.json`);

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
      XRAY_CONFIG_PATH: xrayConfigFile,
      ...(xrayBinPath
        ? {
            XRAY_BIN_PATH: xrayBinPath,
            // The server always assigns the primary inbound port 443, which
            // needs root to bind — remap it to something unprivileged so a
            // *real* xray-core process can actually start as this test's
            // user. See agent/src/xray/config-builder.ts. Only ever set here.
            AGENT_PRIMARY_INBOUND_PORT_OVERRIDE: '18443',
            AGENT_XRAY_LOGLEVEL: 'debug',
          }
        : {}),
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

  return { proc, hostname, stateFile, xrayConfigFile, log: () => log };
}

export function stopLocalAgent(handle: LocalAgentHandle) {
  handle.proc.kill('SIGTERM');

  // Belt-and-suspenders: `npx tsx src/index.ts` doesn't reliably forward
  // SIGTERM down through npx's own child process to the actual tsx/node
  // process — confirmed live (a real xray-core child, spawned by
  // agent/'s own graceful-shutdown-on-SIGTERM handler, was still alive and
  // *still listening on its Reality inbound port* minutes after
  // handle.proc.kill() returned). An orphaned real xray-core process from a
  // *previous* test run squatting on the same fallback port then made a
  // *later* run's client connect to stale, mismatched key material — macOS
  // happily lets multiple processes LISTEN on the same TCP port
  // concurrently and picks one at accept() time essentially at random.
  // Only ever needed here (tunnel.spec.ts's real-binary agents); harmless
  // no-op for nodes.spec.ts's simulated-mode ones (nothing to match).
  try {
    execFileSync('pkill', ['-f', handle.xrayConfigFile]);
  } catch {
    // pkill exits non-zero when nothing matched — the common case once the
    // graceful shutdown above actually did its job.
  }

  for (const file of [handle.stateFile, handle.xrayConfigFile]) {
    try {
      fs.unlinkSync(file);
    } catch {
      // already gone, fine
    }
  }
}

export async function createBootstrapToken(page: Page, pool: string, type: string): Promise<string> {
  await page.getByRole('button', { name: 'Создать bootstrap-токен' }).click();
  await page.getByTestId('bootstrap-pool-select').selectOption(pool);
  await page.getByTestId('bootstrap-type-select').selectOption(type);
  await page.getByRole('button', { name: 'Создать', exact: true }).click();
  const token = await page.getByTestId('bootstrap-token-value').textContent();
  if (!token) throw new Error('Bootstrap token dialog did not render a token');
  await page.getByRole('button', { name: '✕' }).click();
  return token.trim();
}

const PLATFORM_BIN_DIR: Record<string, string> = {
  'darwin-arm64': 'mac-arm64',
  'darwin-x64': 'mac-x64',
  'linux-x64': 'linux-x64',
  'linux-arm64': 'linux-arm64',
  'win32-x64': 'win-x64',
};

/**
 * Finds a real xray-core binary for tunnel.spec.ts to actually run (as
 * opposed to nodes.spec.ts, which is fine with XraySupervisor's simulated
 * mode). Checks, in order:
 *   1. XRAY_BIN_PATH env var (same variable agent/'s XraySupervisor reads).
 *   2. desktop/resources/bin/<platform>/xray[.exe] — wherever
 *      `npm run fetch:xray` (desktop/scripts/fetch-xray-core.mjs) already
 *      downloaded one for desktop dev, so this test doesn't need its own
 *      separate fetch step on a machine that already has the desktop app
 *      set up.
 * Returns null if neither is found — callers should test.skip() rather than
 * fail, since not every dev machine running the e2e suite has fetched a
 * real binary (nodes.spec.ts deliberately doesn't require one).
 */
export function resolveXrayBinaryPath(): string | null {
  if (process.env.XRAY_BIN_PATH && fs.existsSync(process.env.XRAY_BIN_PATH)) {
    return process.env.XRAY_BIN_PATH;
  }

  const key = `${process.platform}-${process.arch}`;
  const dir = PLATFORM_BIN_DIR[key];
  if (!dir) return null;

  const bin = path.join(
    __dirname,
    '../../desktop/resources/bin',
    dir,
    process.platform === 'win32' ? 'xray.exe' : 'xray'
  );
  return fs.existsSync(bin) ? bin : null;
}
