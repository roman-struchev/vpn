/**
 * Smart Reconnect Backoff — same invariants as the Android client
 * (android/.../vpn/ReconnectBackoffPolicy.java) and
 * docs/ROADMAP_PROGRESS.md §1.5 / docs/PLAN.md §6: a naive "drop -> retry
 * immediately on another node/fingerprint" pattern is itself a detectable
 * signature that extends a TSPU ban window from 120s to 600s.
 */
export const MIN_INITIAL_BACKOFF_SEC = 15;
export const MAX_INITIAL_BACKOFF_SEC = 20;
const MAX_BACKOFF_SEC = 300;

export type Fingerprint = 'firefox' | 'edge';

export interface BackoffDecision {
  delaySeconds: number;
  switchNode: boolean;
}

export class ReconnectBackoffPolicy {
  private readonly initialBackoffSec: number;
  private readonly maxRetriesBeforeNodeSwitch: number;
  private readonly fingerprint: Fingerprint;
  private consecutiveFailuresOnNode = 0;

  constructor(initialBackoffSec: number, maxRetriesBeforeNodeSwitch: number, fingerprint: string) {
    this.initialBackoffSec = clamp(initialBackoffSec, MIN_INITIAL_BACKOFF_SEC, MAX_INITIAL_BACKOFF_SEC);
    this.maxRetriesBeforeNodeSwitch = clamp(maxRetriesBeforeNodeSwitch, 2, 3);
    if (fingerprint !== 'firefox' && fingerprint !== 'edge') {
      throw new Error(`fingerprint must be a real browser fingerprint (firefox|edge), got: ${fingerprint}`);
    }
    this.fingerprint = fingerprint;
  }

  /** Fixed for the whole session; a reconnect must never re-roll this. */
  getFingerprint(): Fingerprint {
    return this.fingerprint;
  }

  getConsecutiveFailuresOnNode(): number {
    return this.consecutiveFailuresOnNode;
  }

  onFailure(): BackoffDecision {
    this.consecutiveFailuresOnNode += 1;

    const switchNode = this.consecutiveFailuresOnNode >= this.maxRetriesBeforeNodeSwitch;
    const delaySeconds = Math.min(this.initialBackoffSec * this.consecutiveFailuresOnNode, MAX_BACKOFF_SEC);

    if (switchNode) {
      this.consecutiveFailuresOnNode = 0;
    }
    return { delaySeconds, switchNode };
  }

  onSuccess(): void {
    this.consecutiveFailuresOnNode = 0;
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
