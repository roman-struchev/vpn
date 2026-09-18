import { describe, it, expect, beforeEach, vi } from 'vitest';
import { DiagnosticsCollector } from './diagnostics.js';

vi.mock('./logger.js', () => ({
  logger: { error: vi.fn(), warn: vi.fn(), info: vi.fn(), debug: vi.fn() },
}));

describe('DiagnosticsCollector', () => {
  let collector: DiagnosticsCollector;

  beforeEach(() => {
    collector = new DiagnosticsCollector();
  });

  const event = (message: string, code = 'XRAY_EXITED') => ({
    severity: 'ERROR' as const,
    component: 'xray',
    code,
    message,
  });

  it('folds repeats of one issue into a single queued entry', () => {
    // A crash loop between two drains must not queue hundreds of entries —
    // the buffer exists on a node that is already in trouble.
    for (let i = 0; i < 200; i++) collector.report(event('Xray exited with code 1'));

    expect(collector.size).toBe(1);
    expect(collector.drain()).toHaveLength(1);
  });

  it('keeps genuinely different failures apart', () => {
    collector.report(event('Xray exited with code 1'));
    collector.report(event('Failed to apply configuration', 'CONFIG_APPLY_FAILED'));

    expect(collector.drain()).toHaveLength(2);
  });

  it('drops the oldest once full and says so in the next drain', () => {
    for (let i = 0; i < 80; i++) collector.report(event(`distinct failure ${i}`));

    const drained = collector.drain();
    expect(drained.length).toBeLessThanOrEqual(51); // 50 buffered + the notice
    const notice = drained.find((e) => e.code === 'REPORTS_DROPPED');
    expect(notice, 'a truncated buffer must be visible in the data, not silent').toBeDefined();
    expect(notice!.message).toMatch(/Dropped \d+ diagnostic report/);
  });

  it('clips an enormous detail rather than shipping a whole log', () => {
    collector.report({ ...event('boom'), detail: 'x'.repeat(50_000) });

    expect(collector.drain()[0].detail!.length).toBeLessThanOrEqual(2000);
  });

  it('ignores a report with no usable message', () => {
    collector.report(event(''));
    collector.report(event('   '));

    expect(collector.size).toBe(0);
    expect(collector.drain()).toEqual([]);
  });

  it('empties on drain so the same failures are not reported twice', () => {
    collector.report(event('Xray exited with code 1'));

    expect(collector.drain()).toHaveLength(1);
    expect(collector.drain()).toHaveLength(0);
  });
});
