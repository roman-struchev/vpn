/**
 * Collects the failures this node hits and hands them to the gRPC client to
 * ship to the server (AgentMessage.diagnostics -> DiagnosticsService).
 *
 * Why a queue rather than sending on the spot: the moments worth reporting
 * are exactly the moments the node is unwell — xray crash-looping, the
 * control-plane stream down — so reporting must (a) survive having nowhere to
 * send right now, (b) never block or throw into the failing code path, and
 * (c) not turn a loop into a flood. Hence a bounded in-memory buffer that
 * folds repeats into a count, drains on a timer, and silently drops the
 * oldest entry when full.
 *
 * Nothing here retries a failed send: the same failure will simply be
 * reported again the next time it happens, and a node that cannot reach the
 * server has a much louder problem than a lost error report.
 */
import { logger } from './logger.js';

export type Severity = 'ERROR' | 'WARN';

export interface DiagnosticEvent {
  severity: Severity;
  component: string;
  code: string;
  message: string;
  detail?: string;
  context?: Record<string, string>;
  occurredAtEpochMs: number;
}

/** Distinct issues held at once; beyond this the oldest is dropped. */
const MAX_QUEUE_SIZE = 50;
/** Per event: enough of a stack trace to be useful, not a whole log file. */
const MAX_DETAIL_LENGTH = 2000;
const MAX_MESSAGE_LENGTH = 500;

export class DiagnosticsCollector {
  private queue: DiagnosticEvent[] = [];
  private droppedSinceLastDrain = 0;

  /**
   * Repeats of an already-queued issue only bump its timestamp, so a process
   * restarting in a loop between two drains costs one entry rather than
   * hundreds. The server aggregates across drains as well (by fingerprint);
   * this is the same idea applied before anything is sent at all.
   */
  report(event: Omit<DiagnosticEvent, 'occurredAtEpochMs'> & { occurredAtEpochMs?: number }): void {
    try {
      const message = clip(event.message, MAX_MESSAGE_LENGTH);
      if (!message) return;

      const key = `${event.component}|${event.code}|${message}`;
      const existing = this.queue.find((e) => `${e.component}|${e.code}|${e.message}` === key);
      if (existing) {
        existing.occurredAtEpochMs = event.occurredAtEpochMs ?? Date.now();
        return;
      }

      if (this.queue.length >= MAX_QUEUE_SIZE) {
        this.queue.shift();
        this.droppedSinceLastDrain++;
      }

      this.queue.push({
        severity: event.severity,
        component: event.component || 'unknown',
        code: event.code || 'UNSPECIFIED',
        message,
        detail: clip(event.detail, MAX_DETAIL_LENGTH),
        context: event.context,
        occurredAtEpochMs: event.occurredAtEpochMs ?? Date.now(),
      });
    } catch {
      // Reporting an error must never raise one.
    }
  }

  /** Everything buffered, clearing the buffer. Empty when there is nothing to say. */
  drain(): DiagnosticEvent[] {
    if (this.queue.length === 0) return [];
    const drained = this.queue;
    this.queue = [];
    if (this.droppedSinceLastDrain > 0) {
      // Say so in-band: an analysis reading "X occurrences" should know when
      // the real number was higher than what got through.
      drained.push({
        severity: 'WARN',
        component: 'diagnostics',
        code: 'REPORTS_DROPPED',
        message: `Dropped ${this.droppedSinceLastDrain} diagnostic report(s): more distinct failures than the agent's buffer holds`,
        occurredAtEpochMs: Date.now(),
      });
      this.droppedSinceLastDrain = 0;
    }
    return drained;
  }

  get size(): number {
    return this.queue.length;
  }
}

/** The one collector the agent reports into. */
export const diagnostics = new DiagnosticsCollector();

/**
 * Reports an error and logs it, so a failure path never has to remember to do
 * both — the call reads like the logging it replaces.
 */
export function reportError(
  component: string,
  code: string,
  message: string,
  error?: unknown,
  context?: Record<string, string>
): void {
  const detail = error instanceof Error ? (error.stack ?? error.message) : error !== undefined ? String(error) : undefined;
  logger.error(`[${component}/${code}] ${message}${detail ? `: ${detail}` : ''}`);
  diagnostics.report({ severity: 'ERROR', component, code, message, detail, context });
}

/** Same, for something worth knowing about that did not stop anything. */
export function reportWarning(
  component: string,
  code: string,
  message: string,
  context?: Record<string, string>
): void {
  logger.warn(`[${component}/${code}] ${message}`);
  diagnostics.report({ severity: 'WARN', component, code, message, context });
}

/**
 * Catches what no explicit call site will: an unhandled rejection or an
 * uncaught exception. Without this the process would report nothing about the
 * failure that is about to (or already did) end it.
 */
export function installProcessHandlers(): void {
  process.on('unhandledRejection', (reason) => {
    reportError('process', 'UNHANDLED_REJECTION', 'Unhandled promise rejection', reason);
  });
  process.on('uncaughtException', (error) => {
    reportError('process', 'UNCAUGHT_EXCEPTION', 'Uncaught exception', error);
    // Deliberately not exiting: the supervisor loop below this has always
    // been the thing that decides whether the agent can carry on, and
    // crashing here would also throw away the report we just queued.
  });
}

function clip(value: string | undefined, max: number): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  return trimmed.length <= max ? trimmed : trimmed.slice(0, max);
}
