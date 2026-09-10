import { describe, expect, it } from 'vitest';
import { evaluateCensorship } from '../src/shared/censorshipVerdict';

describe('evaluateCensorship', () => {
  it('both reachable means OK', () => {
    expect(evaluateCensorship(true, true)).toBe('OK');
  });

  it('whitelist reachable but foreign blocked means operator restriction', () => {
    expect(evaluateCensorship(true, false)).toBe('OPERATOR_RESTRICTION');
  });

  it('neither reachable means generic connectivity problem', () => {
    expect(evaluateCensorship(false, false)).toBe('NO_CONNECTIVITY');
  });

  it('foreign reachable alone is still OK', () => {
    expect(evaluateCensorship(false, true)).toBe('OK');
  });
});
