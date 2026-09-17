import { describe, it, expect } from 'vitest';
import { computeClientDiff } from './handler-api.js';

const c = (emailTag: string, uuid: string) => ({ emailTag, uuid });

describe('computeClientDiff', () => {
  it('reports a newly registered device as an addition only', () => {
    const diff = computeClientDiff([c('user_1_dev_1', 'u1')], [c('user_1_dev_1', 'u1'), c('user_2_dev_2', 'u2')]);
    expect(diff.added).toEqual([c('user_2_dev_2', 'u2')]);
    expect(diff.removedEmails).toEqual([]);
  });

  it('reports a revoked device as a removal only', () => {
    const diff = computeClientDiff([c('user_1_dev_1', 'u1'), c('user_2_dev_2', 'u2')], [c('user_1_dev_1', 'u1')]);
    expect(diff.added).toEqual([]);
    expect(diff.removedEmails).toEqual(['user_2_dev_2']);
  });

  it('replaces a user whose uuid changed (xray keys users by email)', () => {
    const diff = computeClientDiff([c('user_1_dev_1', 'old')], [c('user_1_dev_1', 'new')]);
    expect(diff.removedEmails).toEqual(['user_1_dev_1']);
    expect(diff.added).toEqual([c('user_1_dev_1', 'new')]);
  });

  it('is a no-op when nothing changed', () => {
    const same = [c('user_1_dev_1', 'u1'), c('user_2_dev_2', 'u2')];
    expect(computeClientDiff(same, [...same])).toEqual({ added: [], removedEmails: [] });
  });
});
