export type RelayMode = 'OFF' | 'TIMED' | 'ALWAYS';

export interface ModeButton {
  label: string;
  mode: RelayMode;
  durationMs?: number;
}

export interface CurrentRelayMode {
  mode: RelayMode;
  durationMs: number | null;
}

/**
 * Whether `btn` is the one currently active. Split out of P2pRelaySection.tsx
 * as a plain function so this exact predicate gets direct unit test coverage
 * (this project has no component-rendering test setup — see vitest.config.ts,
 * node environment, no jsdom/RTL — so pulling pure logic out into its own
 * testable module is the established pattern here, same as destinationAcl.ts
 * and signalEnvelope.ts).
 *
 * A previous version only checked `current.mode === btn.mode`. That's fine
 * for OFF/ALWAYS (each has exactly one button), but TIMED has two buttons
 * (1h, 8h) that both set mode:'TIMED' — so once ANY timed window was active,
 * both lit up together, since nothing distinguished which duration produced
 * the current window. durationMs (persisted alongside expiresAtEpochMs —
 * see TokenStore#p2pRelayDurationMs) is what actually answers that; a bare
 * mode-only check for TIMED buttons is the bug, not a variant of the fix.
 */
/**
 * Whether switching to `next` still has to ask the user to accept the terms.
 *
 * Consent is one-time and only ever about *starting* to relay. It used to be
 * a gate in front of the whole section: the mode buttons did not exist until
 * you pressed "Agree and enable" — a button that, despite its label, enabled
 * nothing and left you on an all-OFF selector. Asking at the moment a mode is
 * actually picked makes the button's promise true, and switching relaying
 * back off never needs agreeing to anything (the Android client asks the same
 * question the same way — see P2pRelaySettingsActivity#needsConsent).
 */
export function needsP2pConsent(next: RelayMode, alreadyAccepted: boolean): boolean {
  return next !== 'OFF' && !alreadyAccepted;
}

export function isModeButtonActive(current: CurrentRelayMode | null | undefined, btn: ModeButton): boolean {
  if (current?.mode !== btn.mode) return false;
  if (btn.mode !== 'TIMED') return true;
  return current.durationMs === btn.durationMs;
}
