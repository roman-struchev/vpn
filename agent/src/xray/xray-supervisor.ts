import fs from 'fs';
import path from 'path';
import { spawn, ChildProcess } from 'child_process';
import { logger } from '../utils/logger.js';
import { buildXrayConfig, hasStructuralChanges, ServerConfigSyncPayload } from './config-builder.js';

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
      } else {
        const structural = hasStructuralChanges(this.lastConfigSync, syncPayload);
        if (structural) {
          logger.info('Structural inbound or transport changes detected, performing graceful Xray restart');
          await this.restart();
        } else {
          logger.info('Only client/credential changes detected; applied to disk without dropping active tunnels');
        }
      }

      this.lastConfigSync = syncPayload;
      return true;
    } catch (err) {
      logger.error('Failed to apply Xray configuration:', err);
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
      this.process = spawn(this.binaryPath, ['run', '-c', this.configPath], {
        stdio: ['ignore', 'pipe', 'pipe'],
      });

      this.process.stdout?.on('data', data => {
        logger.info(`[xray-core] ${data.toString().trim()}`);
      });

      this.process.stderr?.on('data', data => {
        logger.warn(`[xray-core stderr] ${data.toString().trim()}`);
      });

      this.process.on('error', err => {
        logger.warn(`Failed to spawn xray binary (${this.binaryPath}): ${err.message}. Running in simulated mode.`);
        this.isMockMode = true;
        this.isRunning = true;
      });

      this.process.on('exit', (code, signal) => {
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
          logger.warn(`Xray process exited unexpectedly with code ${code}, signal ${signal}. Triggering watchdog auto-restart.`);
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
    if (this.watchdogTimer) {
      clearTimeout(this.watchdogTimer);
      this.watchdogTimer = null;
    }
    if (this.stabilityTimer) {
      clearTimeout(this.stabilityTimer);
      this.stabilityTimer = null;
    }

    if (this.process) {
      logger.info('Stopping Xray process...');
      this.process.kill('SIGTERM');
      this.process = null;
    }
    this.isRunning = false;
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

