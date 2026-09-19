import { describe, expect, it } from 'vitest';
import { isModeButtonActive, needsP2pConsent, type ModeButton } from '../src/renderer/src/components/p2pModeButtons';

const offBtn: ModeButton = { label: 'Off', mode: 'OFF' };
const oneHourBtn: ModeButton = { label: '1h', mode: 'TIMED', durationMs: 3_600_000 };
const eightHourBtn: ModeButton = { label: '8h', mode: 'TIMED', durationMs: 8 * 3_600_000 };
const alwaysBtn: ModeButton = { label: 'Always', mode: 'ALWAYS' };

describe('isModeButtonActive', () => {
  it('regression: only the 1h button is active when a 1h window is running, not the 8h one too', () => {
    const current = { mode: 'TIMED' as const, durationMs: 3_600_000 };
    expect(isModeButtonActive(current, oneHourBtn)).toBe(true);
    expect(isModeButtonActive(current, eightHourBtn)).toBe(false);
  });

  it('regression: only the 8h button is active when an 8h window is running, not the 1h one too', () => {
    const current = { mode: 'TIMED' as const, durationMs: 8 * 3_600_000 };
    expect(isModeButtonActive(current, eightHourBtn)).toBe(true);
    expect(isModeButtonActive(current, oneHourBtn)).toBe(false);
  });

  it('OFF and ALWAYS ignore durationMs entirely (each has only one button, no ambiguity to resolve)', () => {
    expect(isModeButtonActive({ mode: 'OFF', durationMs: null }, offBtn)).toBe(true);
    expect(isModeButtonActive({ mode: 'ALWAYS', durationMs: null }, alwaysBtn)).toBe(true);
    expect(isModeButtonActive({ mode: 'ALWAYS', durationMs: 3_600_000 }, alwaysBtn)).toBe(true); // stale leftover durationMs must not matter
  });

  it('no button is active for a mode mismatch', () => {
    expect(isModeButtonActive({ mode: 'OFF', durationMs: null }, oneHourBtn)).toBe(false);
    expect(isModeButtonActive({ mode: 'ALWAYS', durationMs: null }, offBtn)).toBe(false);
  });

  it('a TIMED button never matches before any duration has been recorded (durationMs: null)', () => {
    // Can happen right after an app update, before the user has picked a
    // duration again post-upgrade — must fail closed (nothing highlighted),
    // not accidentally match whichever TIMED button happens to be checked first.
    expect(isModeButtonActive({ mode: 'TIMED', durationMs: null }, oneHourBtn)).toBe(false);
    expect(isModeButtonActive({ mode: 'TIMED', durationMs: null }, eightHourBtn)).toBe(false);
  });

  it('handles a null/undefined current mode (not yet loaded) without matching anything', () => {
    expect(isModeButtonActive(null, offBtn)).toBe(false);
    expect(isModeButtonActive(undefined, oneHourBtn)).toBe(false);
  });
});

describe('needsP2pConsent', () => {
  it('asks before relaying starts if the user never accepted', () => {
    expect(needsP2pConsent('TIMED', false)).toBe(true);
    expect(needsP2pConsent('ALWAYS', false)).toBe(true);
  });

  it('asks only once', () => {
    expect(needsP2pConsent('TIMED', true)).toBe(false);
    expect(needsP2pConsent('ALWAYS', true)).toBe(false);
  });

  it('never asks to stop relaying', () => {
    // Turning the feature off can't require agreeing to it — the Android
    // client had the same inversion (see P2pConsentTest).
    expect(needsP2pConsent('OFF', false)).toBe(false);
    expect(needsP2pConsent('OFF', true)).toBe(false);
  });
});
