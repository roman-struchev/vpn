import { describe, expect, it } from 'vitest';
import {
  MAX_INITIAL_BACKOFF_SEC,
  MIN_INITIAL_BACKOFF_SEC,
  ReconnectBackoffPolicy,
} from '../src/shared/reconnectBackoffPolicy';

describe('ReconnectBackoffPolicy', () => {
  it('never pauses less than 15s after the first failure', () => {
    const policy = new ReconnectBackoffPolicy(1, 3, 'firefox');
    const decision = policy.onFailure();
    expect(decision.delaySeconds).toBeGreaterThanOrEqual(MIN_INITIAL_BACKOFF_SEC);
  });

  it('clamps the initial backoff to the 15-20s band from transport_policy', () => {
    const tooLow = new ReconnectBackoffPolicy(0, 3, 'firefox');
    const tooHigh = new ReconnectBackoffPolicy(999, 3, 'firefox');
    expect(tooLow.onFailure().delaySeconds).toBe(MIN_INITIAL_BACKOFF_SEC);
    expect(tooHigh.onFailure().delaySeconds).toBe(MAX_INITIAL_BACKOFF_SEC);
  });

  it('does not switch node before the configured failure threshold', () => {
    const policy = new ReconnectBackoffPolicy(15, 3, 'firefox');
    expect(policy.onFailure().switchNode).toBe(false);
    expect(policy.onFailure().switchNode).toBe(false);
    expect(policy.onFailure().switchNode).toBe(true);
  });

  it('clamps the switch threshold to 2-3', () => {
    const tooEager = new ReconnectBackoffPolicy(15, 1, 'firefox');
    expect(tooEager.onFailure().switchNode).toBe(false);
    expect(tooEager.onFailure().switchNode).toBe(true);

    const tooPatient = new ReconnectBackoffPolicy(15, 10, 'firefox');
    expect(tooPatient.onFailure().switchNode).toBe(false);
    expect(tooPatient.onFailure().switchNode).toBe(false);
    expect(tooPatient.onFailure().switchNode).toBe(true);
  });

  it('resets the failure streak on success', () => {
    const policy = new ReconnectBackoffPolicy(15, 3, 'firefox');
    policy.onFailure();
    policy.onFailure();
    policy.onSuccess();
    expect(policy.getConsecutiveFailuresOnNode()).toBe(0);
    expect(policy.onFailure().switchNode).toBe(false);
  });

  it('never changes fingerprint across retries or node switches within a session', () => {
    const policy = new ReconnectBackoffPolicy(15, 2, 'edge');
    const fp = policy.getFingerprint();
    for (let i = 0; i < 10; i++) {
      policy.onFailure();
      expect(policy.getFingerprint()).toBe(fp);
    }
  });

  it('rejects a fingerprint that is not a real browser', () => {
    expect(() => new ReconnectBackoffPolicy(15, 3, 'chrome')).toThrow();
    expect(() => new ReconnectBackoffPolicy(15, 3, '')).toThrow();
  });
});
