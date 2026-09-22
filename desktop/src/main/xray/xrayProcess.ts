import { app } from 'electron';
import { type ChildProcessWithoutNullStreams, spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { getGeoDataDir, getXrayBinaryPath } from './binaryManager';

/**
 * Owns exactly one xray-core child process at a time. Config is written to
 * a userData-local file and passed via `-c`; the process is expected to
 * keep running until stop() is called or it exits/crashes on its own
 * (surfaced through onExit for the caller's reconnect logic to react to).
 */
export class XrayProcess {
  private child: ChildProcessWithoutNullStreams | null = null;
  private readonly configPath: string;

  constructor() {
    const dir = path.join(app.getPath('userData'), 'xray-runtime');
    mkdirSync(dir, { recursive: true });
    this.configPath = path.join(dir, 'config.json');
  }

  isRunning(): boolean {
    return this.child !== null && this.child.exitCode === null;
  }

  start(config: object, onExit: (code: number | null, signal: NodeJS.Signals | null) => void): void {
    if (this.isRunning()) {
      throw new Error('xray is already running; call stop() first');
    }

    writeFileSync(this.configPath, JSON.stringify(config, null, 2), 'utf-8');

    const binary = getXrayBinaryPath();
    const child = spawn(binary, ['run', '-c', this.configPath], {
      env: { ...process.env, XRAY_LOCATION_ASSET: getGeoDataDir() },
      windowsHide: true,
    });

    child.stdout.on('data', (chunk) => console.log(`[xray] ${chunk.toString().trim()}`));
    child.stderr.on('data', (chunk) => console.error(`[xray] ${chunk.toString().trim()}`));
    child.on('exit', (code, signal) => {
      // Only if it is still the current process. A process stop()ped a
      // moment ago exits *after* its replacement has started, and used to
      // null out the replacement's handle here — orphaning it (never
      // stopped again) — and report its own exit as the new one crashing.
      if (this.child !== child) return;
      this.child = null;
      onExit(code, signal);
    });

    this.child = child;
  }

  /**
   * Stops the current process and resolves once it has actually exited, so
   * the next start() does not race it for the same local ports (a quick
   * region change used to hit "address already in use" and then wait out a
   * full backoff). Escalates to SIGKILL if it does not go quietly.
   */
  stop(): Promise<void> {
    const child = this.child;
    if (!child) return Promise.resolve();
    this.child = null;
    if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve();
    return new Promise((resolve) => {
      const killTimer = setTimeout(() => child.kill('SIGKILL'), STOP_GRACE_MS);
      child.once('exit', () => {
        clearTimeout(killTimer);
        resolve();
      });
      // SIGTERM is enough on posix; on Windows child_process.kill() sends a
      // forceful terminate regardless of the signal argument.
      child.kill('SIGTERM');
    });
  }
}

/** How long a stopping xray gets to exit on its own before it is killed. */
const STOP_GRACE_MS = 3000;
