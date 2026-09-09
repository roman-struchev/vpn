import { describe, it, expect } from 'vitest';
import { StatsCollector } from './stats-collector.js';

describe('StatsCollector', () => {
  it('should initialize and safely return empty deltas when Xray API is unreachable', async () => {
    // Port 59999 is closed, should safely handle error and return empty array
    const collector = new StatsCollector('127.0.0.1:59999');
    const deltas = await collector.collectDeltas();
    expect(deltas).toEqual([]);
  });
});
