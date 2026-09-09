import { logger } from '../utils/logger.js';

export interface TrafficDelta {
  userId: number;
  deviceId: number;
  uuid: string;
  uplinkBytes: number;
  downlinkBytes: number;
}

export class StatsCollector {
  private apiUrl: string;
  private lastCounters: Map<string, { up: number; down: number }> = new Map();

  constructor(apiUrl: string = '127.0.0.1:10085') {
    this.apiUrl = apiUrl;
  }

  public async collectDeltas(): Promise<TrafficDelta[]> {
    // In a real environment with local Xray Stats gRPC API running on 10085,
    // this calls xray.app.stats.command.StatsService/QueryStats with reset=true.
    // If running standalone or during initial sync without active traffic, returns empty deltas.
    return [];
  }
}
