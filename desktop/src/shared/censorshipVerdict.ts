/**
 * Decision function behind the "honest blocking screen" (PLAN.md §6): probe a
 * known-reachable Russian host and a foreign test host. If the whitelisted
 * host answers but the foreign one does not, the operator — not our
 * service — is restricting traffic.
 *
 * Pure function so it's unit-testable without real network I/O; the actual
 * probing lives in src/main/vpn/censorshipProbe.ts.
 */
export type CensorshipResult = 'OK' | 'OPERATOR_RESTRICTION' | 'NO_CONNECTIVITY';

export function evaluateCensorship(whitelistHostReachable: boolean, foreignHostReachable: boolean): CensorshipResult {
  if (foreignHostReachable) {
    // The target we actually care about works, regardless of the whitelist probe.
    return 'OK';
  }
  if (whitelistHostReachable) {
    return 'OPERATOR_RESTRICTION';
  }
  return 'NO_CONNECTIVITY';
}
