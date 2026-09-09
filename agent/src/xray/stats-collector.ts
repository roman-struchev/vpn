import path from 'path';
import { fileURLToPath } from 'url';
import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';
import { logger } from '../utils/logger.js';

export interface TrafficDelta {
  userId: number;
  deviceId: number;
  uuid: string;
  uplinkBytes: number;
  downlinkBytes: number;
}

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export class StatsCollector {
  private apiUrl: string;
  private client: any = null;
  private isAvailable: boolean = true;

  constructor(apiUrl: string = '127.0.0.1:10085') {
    this.apiUrl = apiUrl;
    this.initClient();
  }

  private initClient(): void {
    try {
      const protoPath = path.resolve(__dirname, '../../proto/xray-stats.proto');
      const packageDefinition = protoLoader.loadSync(protoPath, {
        keepCase: false,
        longs: String,
        enums: String,
        defaults: true,
        oneofs: true,
      });
      const proto = (grpc.loadPackageDefinition(packageDefinition) as any).xray.app.stats.command;
      this.client = new proto.StatsService(this.apiUrl, grpc.credentials.createInsecure());
    } catch (err) {
      logger.warn('Failed to initialize Xray Stats gRPC client (will retry on query):', err);
      this.client = null;
    }
  }

  public async collectDeltas(): Promise<TrafficDelta[]> {
    if (!this.client) {
      this.initClient();
      if (!this.client) return [];
    }

    return new Promise((resolve) => {
      const deadline = new Date(Date.now() + 2000); // 2 second timeout
      this.client.queryStats(
        { pattern: 'user>>>', reset: true },
        { deadline },
        (err: any, response: any) => {
          if (err) {
            // Xray may be offline or starting up; do not spam logs
            if (this.isAvailable) {
              logger.debug(`Xray Stats API not reachable at ${this.apiUrl}: ${err.message}`);
              this.isAvailable = false;
            }
            return resolve([]);
          }

          this.isAvailable = true;
          const statsList: Array<{ name: string; value: string | number }> = response?.stat || [];
          if (statsList.length === 0) {
            return resolve([]);
          }

          // Group by user/device email
          // Pattern: user>>><email>>>>traffic>>><uplink|downlink>
          const deltaMap = new Map<string, { userId: number; deviceId: number; up: number; down: number }>();

          for (const stat of statsList) {
            const parts = stat.name.split('>>>');
            if (parts.length >= 4 && parts[0] === 'user' && parts[2] === 'traffic') {
              const email = parts[1];
              const direction = parts[3]; // 'uplink' or 'downlink'
              const value = typeof stat.value === 'string' ? parseInt(stat.value, 10) : Number(stat.value) || 0;

              if (value <= 0) continue;

              // Parse user_123_dev_456
              const emailMatch = email.match(/^user_(\d+)_dev_(\d+)$/);
              const userId = emailMatch ? parseInt(emailMatch[1], 10) : 0;
              const deviceId = emailMatch ? parseInt(emailMatch[2], 10) : 0;

              if (!deltaMap.has(email)) {
                deltaMap.set(email, { userId, deviceId, up: 0, down: 0 });
              }

              const entry = deltaMap.get(email)!;
              if (direction === 'uplink') {
                entry.up += value;
              } else if (direction === 'downlink') {
                entry.down += value;
              }
            }
          }

          const deltas: TrafficDelta[] = [];
          for (const [email, data] of deltaMap.entries()) {
            if (data.up > 0 || data.down > 0) {
              deltas.push({
                userId: data.userId,
                deviceId: data.deviceId,
                uuid: email,
                uplinkBytes: data.up,
                downlinkBytes: data.down,
              });
            }
          }

          resolve(deltas);
        }
      );
    });
  }
}
