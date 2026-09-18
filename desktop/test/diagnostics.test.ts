import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DiagnosticsCollector, DiagnosticsSink } from '../src/main/diagnostics';

vi.mock('electron', () => ({ app: { getVersion: () => '0.1.13' } }));

class RecordingSink implements DiagnosticsSink {
  payloads: any[] = [];
  shouldFail = false;

  async postDiagnostics(payload: unknown): Promise<unknown> {
    if (this.shouldFail) throw new Error('server unreachable');
    this.payloads.push(payload);
    return { status: 'RECEIVED' };
  }
}

describe('desktop diagnostics collector', () => {
  let sink: RecordingSink;
  let collector: DiagnosticsCollector;

  beforeEach(() => {
    sink = new RecordingSink();
    collector = new DiagnosticsCollector(sink, '0.1.13', 'device-uuid-1');
  });

  const event = (message: string, code = 'PROFILE_LOAD_FAILED') => ({
    severity: 'ERROR' as const,
    component: 'vpn',
    code,
    message,
  });

  it('sends what it collected, tagged with the source and build', async () => {
    collector.report(event('Failed to load the VPN profile'));

    await collector.flush();

    expect(sink.payloads).toHaveLength(1);
    expect(sink.payloads[0].source).toBe('DESKTOP');
    expect(sink.payloads[0].appVersion).toBe('0.1.13');
    expect(sink.payloads[0].reporterId).toBe('device-uuid-1');
    expect(sink.payloads[0].events[0].code).toBe('PROFILE_LOAD_FAILED');
  });

  it('folds a retry loop into one entry instead of one per attempt', () => {
    for (let i = 0; i < 100; i++) collector.report(event('Failed to load the VPN profile'));

    expect(collector.size).toBe(1);
  });

  it('keeps different failures apart', () => {
    collector.report(event('Failed to load the VPN profile'));
    collector.report(event('Failed to disable the system proxy', 'PROXY_DISABLE_FAILED'));

    expect(collector.size).toBe(2);
  });

  it('never throws or re-queues when the server is unreachable', async () => {
    // Being unable to reach the server is one of the conditions worth
    // reporting, so the failure to report must be a no-op, not a growing
    // backlog and definitely not an exception into the caller.
    sink.shouldFail = true;
    collector.report(event('Failed to load the VPN profile'));

    await expect(collector.flush()).resolves.toBeUndefined();
    expect(collector.size).toBe(0);
  });

  it('flushes nothing when nothing broke', async () => {
    await collector.flush();

    expect(sink.payloads).toHaveLength(0);
  });

  it('caps the buffer and reports that it had to drop reports', async () => {
    for (let i = 0; i < 60; i++) collector.report(event(`distinct failure ${i}`));

    await collector.flush();

    const events = sink.payloads[0].events;
    expect(events.length).toBeLessThanOrEqual(31); // 30 buffered + the notice
    expect(events.some((e: any) => e.code === 'REPORTS_DROPPED')).toBe(true);
  });

  it('clips a huge stack trace instead of shipping it whole', async () => {
    collector.report({ ...event('boom'), detail: 'x'.repeat(50_000) });

    await collector.flush();

    expect(sink.payloads[0].events[0].detail.length).toBeLessThanOrEqual(2000);
  });
});
