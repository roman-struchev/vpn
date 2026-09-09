import fs from 'fs';
import path from 'path';
import { spawn, ChildProcess } from 'child_process';
import { logger } from '../utils/logger.js';
import { buildXrayConfig, ServerConfigSyncPayload } from './config-builder.js';

export class XraySupervisor {
  private configPath: string;
  private binaryPath: string;
  private process: ChildProcess | null = null;
  private isRunning: boolean = false;
  private isMockMode: boolean = false;

  constructor(configPath: string, binaryPath: string = 'xray') {
    this.configPath = configPath;
    this.binaryPath = binaryPath;
  }

  public async applyConfig(syncPayload: ServerConfigSyncPayload): Promise<boolean> {
    try {
      const configJson = buildXrayConfig(syncPayload);
      const dir = path.dirname(this.configPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }

      fs.writeFileSync(this.configPath, JSON.stringify(configJson, null, 2), 'utf-8');
      logger.info(`Wrote updated Xray configuration to ${this.configPath} (version ${syncPayload.configVersion})`);

      await this.restart();
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
        logger.warn(`Xray process exited with code ${code}, signal ${signal}`);
        this.isRunning = false;
        this.process = null;
      });

      this.isRunning = true;
      logger.info(`Xray process started with config ${this.configPath}`);
    } catch (err) {
      logger.warn(`Could not start xray process, falling back to mock mode: ${err}`);
      this.isMockMode = true;
      this.isRunning = true;
    }
  }

  public async stop(): Promise<void> {
    if (this.process) {
      logger.info('Stopping Xray process...');
      this.process.kill('SIGTERM');
      this.process = null;
    }
    this.isRunning = false;
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
}
