/**
 * Phase 10 hardening ("Пул резервных доменов для API сервера"): if the
 * primary API domain is blocked/poisoned, fall through to the next
 * configured backup domain rather than failing outright. Pure, in-memory,
 * per-process — no persistence of "which host worked last" across app
 * restarts. Same contract as the Android client's ApiHostRotation.
 */
export class ApiHostRotation {
  private readonly hosts: string[];
  private currentIndex = 0;

  constructor(hosts: string[]) {
    if (!hosts || hosts.length === 0) {
      throw new Error('At least one API host is required');
    }
    this.hosts = [...hosts];
  }

  current(): string {
    return this.hosts[this.currentIndex];
  }

  size(): number {
    return this.hosts.length;
  }

  /** Advances to the next host (wrapping around) and returns it. */
  advance(): string {
    this.currentIndex = (this.currentIndex + 1) % this.hosts.length;
    return this.current();
  }
}
