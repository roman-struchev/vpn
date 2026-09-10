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
      this.child = null;
      onExit(code, signal);
    });

    this.child = child;
  }

  stop(): void {
    if (!this.child) return;
    // SIGTERM is enough on posix; on Windows child_process.kill() sends a
    // forceful terminate regardless of the signal argument.
    this.child.kill('SIGTERM');
    this.child = null;
  }
}
