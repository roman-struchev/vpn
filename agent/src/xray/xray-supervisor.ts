import fs from 'fs';
import path from 'path';
import { spawn, ChildProcess } from 'child_process';
import { logger } from '../utils/logger.js';
import { buildXrayConfig, hasStructuralChanges, ServerConfigSyncPayload } from './config-builder.js';
import { XrayHandlerApi, computeClientDiff, type XrayClient } from './handler-api.js';
import { reportError, reportWarning } from '../utils/diagnostics.js';

export interface WatchdogStats {
  watchdogAttempts: number;
  lastExitCode: number | null;
  lastExitSignal: string | null;
  lastCrashTimestamp: number | null;
}

export class XraySupervisor {
  private configPath: string;
  private binaryPath: string;
  private process: ChildProcess | null = null;
  private isRunning: boolean = false;
  private isMockMode: boolean = false;
  private isStopping: boolean = false;
  private lastConfigSync: ServerConfigSyncPayload | null = null;
  private readonly handlerApi = new XrayHandlerApi();

  // Watchdog state
  private watchdogAttempts: number = 0;
  private watchdogTimer: NodeJS.Timeout | null = null;
  private stabilityTimer: NodeJS.Timeout | null = null;
  private lastExitCode: number | null = null;
  private lastExitSignal: string | null = null;
  private lastCrashTimestamp: number | null = null;

  constructor(configPath: string, binaryPath: string = 'xray') {
    this.configPath = configPath;
    this.binaryPath = binaryPath;
  }

  public async applyConfig(syncPayload: ServerConfigSyncPayload): Promise<boolean> {
    try {
      const configJson = buildXrayConfig(syncPayload);
      const newConfigContent = JSON.stringify(configJson, null, 2);
      const dir = path.dirname(this.configPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }

      const existingContent = fs.existsSync(this.configPath)
        ? fs.readFileSync(this.configPath, 'utf-8')
        : null;

      // If configuration on disk is byte-for-byte identical, avoid any restart/churn
      if (existingContent === newConfigContent && this.isRunning) {
        logger.info(`Xray configuration is identical (version ${syncPayload.configVersion}), skipping reload`);
        this.lastConfigSync = syncPayload;
        return true;
      }

      fs.writeFileSync(this.configPath, newConfigContent, 'utf-8');
      logger.info(`Wrote updated Xray configuration to ${this.configPath} (version ${syncPayload.configVersion})`);

      if (!this.isRunning) {
        await this.start();
      } else if (
        !hasStructuralChanges(this.lastConfigSync, syncPayload) &&
        (await this.applyClientChangesLive(this.lastConfigSync!, syncPayload))
      ) {
        logger.info(`Applied configuration ${syncPayload.configVersion} live (users only, no Xray restart)`);
      } else {
        logger.info(`Applying updated configuration (version ${syncPayload.configVersion}), restarting Xray...`);
        await this.restart();
      }

      this.lastConfigSync = syncPayload;
      return true;
    } catch (err) {
      reportError('xray', 'CONFIG_APPLY_FAILED', 'Failed to apply Xray configuration', err, {
        configVersion: String(syncPayload?.configVersion ?? 'unknown'),
      });
      return false;
    }
  }

  /**
   * Adds/removes just the users that changed, in the running xray, via its
   * HandlerService API. Returns false if anything at all goes wrong, so the
   * caller falls back to the (correct but disruptive) full restart — a failed
   * live update must never leave the node serving a stale client list.
   */
  private async applyClientChangesLive(
    previous: ServerConfigSyncPayload,
    next: ServerConfigSyncPayload
  ): Promise<boolean> {
    const activeClients = (payload: ServerConfigSyncPayload): XrayClient[] =>
      (payload.clients || [])
        .filter(c => c.isActive)
        .map(c => ({ uuid: c.uuid, emailTag: c.emailTag || `user_${c.userId}_dev_${c.deviceId}` }));

    const diff = computeClientDiff(activeClients(previous), activeClients(next));
    if (!diff.added.length && !diff.removedEmails.length) return true;

    // Both inbounds share one client list (see buildXrayConfig), so every
    // change has to be applied to each of them.
    const inboundTags = ['vless-inbound'];
    if (next.fallbackInbound) inboundTags.push('vless-inbound-fallback');

    try {
      for (const tag of inboundTags) {
        for (const email of diff.removedEmails) {
          await this.handlerApi.removeUser(tag, email);
        }
        for (const client of diff.added) {
          await this.handlerApi.addUser(tag, client.uuid, client.emailTag);
        }
      }
      logger.info(
        `Live user update on ${inboundTags.join(', ')}: +${diff.added.length} / -${diff.removedEmails.length}`
      );
      return true;
    } catch (err) {
      reportWarning('xray', 'LIVE_USER_UPDATE_FAILED',
        `Live user update failed (${err}); falling back to an Xray restart`);
      return false;
    }
  }

  public async start(): Promise<void> {
    if (this.isRunning) return;

    if (!fs.existsSync(this.configPath)) {
      logger.warn(`Xray config file not found at ${this.configPath}, waiting for initial ConfigSync`);
      return;
    }

    try {
      const child = spawn(this.binaryPath, ['run', '-c', this.configPath], {
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      this.process = child;

      child.stdout?.on('data', data => {
        logger.info(`[xray-core] ${data.toString().trim()}`);
      });

      child.stderr?.on('data', data => {
        logger.warn(`[xray-core stderr] ${data.toString().trim()}`);
      });

      child.on('error', err => {
        if (this.process !== child) return;
        logger.warn(`Failed to spawn xray binary (${this.binaryPath}): ${err.message}. Running in simulated mode.`);
        this.isMockMode = true;
        this.isRunning = true;
      });

      child.on('exit', (code, signal) => {
        // Only the CURRENT child's exit means anything. A restart SIGTERMs the
        // previous xray and immediately spawns the next one; the previous one's
        // exit arrives after that, and used to be taken for a crash of the new
        // one — the watchdog then started a second xray. Both kept serving
        // :443 via SO_REUSEPORT, so only one process's traffic stats were ever
        // collected (usage under-counted), and the orphan kept running its stale
        // config forever (revoked devices/blocked users could still connect).
        if (this.process !== child) return;
        this.isRunning = false;
        this.process = null;
        this.lastExitCode = code;
        this.lastExitSignal = signal;

        if (this.stabilityTimer) {
          clearTimeout(this.stabilityTimer);
          this.stabilityTimer = null;
        }

        if (!this.isStopping) {
          this.lastCrashTimestamp = Date.now();
          // The single most important thing a node can tell the server: its
          // proxy died and everyone connected through it was dropped.
          reportError('xray', 'XRAY_EXITED', `Xray exited unexpectedly with code ${code}, signal ${signal}`, undefined, {
            exitCode: String(code),
            signal: String(signal),
            watchdogAttempt: String(this.watchdogAttempts + 1),
          });
          this.scheduleWatchdogRestart();
        } else {
          logger.info(`Xray process stopped cleanly (code ${code}, signal ${signal})`);
        }
      });

      this.isRunning = true;
      logger.info(`Xray process started with config ${this.configPath}`);

      // If Xray stays healthy for 60 seconds, reset the backoff counter
      this.stabilityTimer = setTimeout(() => {
        if (this.isRunning) {
          this.watchdogAttempts = 0;
        }
      }, 60000);

    } catch (err) {
      logger.warn(`Could not start xray process, falling back to mock mode: ${err}`);
      this.isMockMode = true;
      this.isRunning = true;
    }
  }

  private scheduleWatchdogRestart(): void {
    if (this.isStopping || this.watchdogTimer) return;

    const delayMs = Math.min(30000, 1000 * Math.pow(2, this.watchdogAttempts));
    this.watchdogAttempts++;
    logger.info(`Watchdog scheduling Xray restart in ${delayMs}ms (attempt #${this.watchdogAttempts})...`);

    this.watchdogTimer = setTimeout(async () => {
      this.watchdogTimer = null;
      if (!this.isStopping && !this.isRunning) {
        await this.start();
      }
    }, delayMs);
  }

  public async stop(): Promise<void> {
    this.isStopping = true;
    this.handlerApi.close();
    if (this.watchdogTimer) {
      clearTimeout(this.watchdogTimer);
      this.watchdogTimer = null;
    }
    if (this.stabilityTimer) {
      clearTimeout(this.stabilityTimer);
      this.stabilityTimer = null;
    }

    const child = this.process;
    this.process = null;
    this.isRunning = false;
    if (child) {
      logger.info('Stopping Xray process...');
      await terminate(child);
    }
    this.isStopping = false;
  }

  public async restart(): Promise<void> {
    await this.stop();
    await this.start();
  }

  public getStatus(): { isRunning: boolean; isMockMode: boolean } {
    return {
      isRunning: this.isRunning,
      isMockMode: this.isMockMode,
    };
  }

  public getWatchdogStats(): WatchdogStats {
    return {
      watchdogAttempts: this.watchdogAttempts,
      lastExitCode: this.lastExitCode,
      lastExitSignal: this.lastExitSignal,
      lastCrashTimestamp: this.lastCrashTimestamp,
    };
  }
}

const STOP_TIMEOUT_MS = 5000;

/**
 * SIGTERM, then SIGKILL if it hasn't exited within STOP_TIMEOUT_MS. Resolves only once
 * the process is really gone, so a restart never has two xray processes bound to the
 * same ports at once.
 */
function terminate(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null || child.pid === undefined) {
    return Promise.resolve();
  }
  return new Promise(resolve => {
    const killTimer = setTimeout(() => {
      logger.warn(`Xray process ${child.pid} did not exit on SIGTERM within ${STOP_TIMEOUT_MS}ms, sending SIGKILL`);
      child.kill('SIGKILL');
    }, STOP_TIMEOUT_MS);
    child.once('exit', () => {
      clearTimeout(killTimer);
      resolve();
    });
    child.kill('SIGTERM');
  });
}
