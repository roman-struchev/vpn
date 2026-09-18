/**
 * Collects the failures this app hits and ships them to the server
 * (POST /api/v1/client/diagnostics -> DiagnosticsService), so a user
 * reporting "it doesn't connect" is not the only way anyone finds out.
 *
 * Same shape as the node agent's collector (agent/src/utils/diagnostics.ts)
 * and for the same reasons: a failing app must be able to report without
 * blocking, without throwing into the code path that is already failing, and
 * without turning a retry loop into a flood. Repeats of one issue fold into a
 * single queued entry, the buffer is bounded, and it drains on a timer.
 *
 * Deliberately no personal data: the message and detail are the failure's own
 * text, and the server masks addresses and identifiers out of them again on
 * arrival.
 */
import { app } from 'electron';

export type Severity = 'ERROR' | 'WARN';

export interface DiagnosticEvent {
  severity: Severity;
  component: string;
  code: string;
  message: string;
  detail?: string;
  context?: Record<string, string>;
}

const MAX_QUEUE_SIZE = 30;
const MAX_MESSAGE_LENGTH = 500;
const MAX_DETAIL_LENGTH = 2000;
const FLUSH_INTERVAL_MS = 60_000;

/** What the collector needs from the API client, kept tiny so tests need no Electron. */
export interface DiagnosticsSink {
  postDiagnostics(payload: unknown): Promise<unknown>;
}

export class DiagnosticsCollector {
  private queue: DiagnosticEvent[] = [];
  private dropped = 0;
  private timer: NodeJS.Timeout | null = null;

  constructor(
    private readonly sink: DiagnosticsSink,
    private readonly appVersion: string,
    private readonly reporterId: string
  ) {}

  report(event: DiagnosticEvent): void {
    try {
      const message = clip(event.message, MAX_MESSAGE_LENGTH);
      if (!message) return;

      const key = `${event.component}|${event.code}|${message}`;
      if (this.queue.some((e) => `${e.component}|${e.code}|${e.message}` === key)) return;

      if (this.queue.length >= MAX_QUEUE_SIZE) {
        this.queue.shift();
        this.dropped++;
      }
      this.queue.push({ ...event, message, detail: clip(event.detail, MAX_DETAIL_LENGTH) });
    } catch {
      // Reporting a failure must never raise one.
    }
  }

  /** Sends whatever is buffered. Resolves even when the send fails. */
  async flush(): Promise<void> {
    if (this.queue.length === 0) return;
    const events = this.queue;
    this.queue = [];
    if (this.dropped > 0) {
      events.push({
        severity: 'WARN',
        component: 'diagnostics',
        code: 'REPORTS_DROPPED',
        message: `Dropped ${this.dropped} diagnostic report(s): more distinct failures than the client buffer holds`,
      });
      this.dropped = 0;
    }

    try {
      await this.sink.postDiagnostics({
        source: 'DESKTOP',
        appVersion: this.appVersion,
        reporterId: this.reporterId,
        events,
      });
    } catch {
      // The server being unreachable is itself one of the conditions this
      // exists to report, so there is nothing useful to do here — and
      // re-queueing would let an offline spell grow the buffer without bound.
    }
  }

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => void this.flush(), FLUSH_INTERVAL_MS);
    // Do not hold the process open just to ship error reports.
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  get size(): number {
    return this.queue.length;
  }
}

let collector: DiagnosticsCollector | null = null;

/** Wires the collector up once, at startup, with the app's own API client. */
export function initDiagnostics(sink: DiagnosticsSink, reporterId: string): DiagnosticsCollector {
  const version = (() => {
    try {
      return app.getVersion();
    } catch {
      return 'dev';
    }
  })();
  collector = new DiagnosticsCollector(sink, version, reporterId);
  collector.start();

  process.on('unhandledRejection', (reason) => {
    reportError('process', 'UNHANDLED_REJECTION', 'Unhandled promise rejection', reason);
  });
  process.on('uncaughtException', (error) => {
    // Send this one immediately: the app may not survive to the next tick,
    // let alone the next flush.
    reportError('process', 'UNCAUGHT_EXCEPTION', 'Uncaught exception in the main process', error);
    void collector?.flush();
  });

  return collector;
}

export function reportError(component: string, code: string, message: string, error?: unknown, context?: Record<string, string>): void {
  const detail = error instanceof Error ? (error.stack ?? error.message) : error !== undefined ? String(error) : undefined;
  collector?.report({ severity: 'ERROR', component, code, message, detail, context });
}

export function reportWarning(component: string, code: string, message: string, context?: Record<string, string>): void {
  collector?.report({ severity: 'WARN', component, code, message, context });
}

/** Ships anything still buffered — called on quit, so a failure right before exit still lands. */
export async function flushDiagnostics(): Promise<void> {
  await collector?.flush();
}

function clip(value: string | undefined, max: number): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  return trimmed.length <= max ? trimmed : trimmed.slice(0, max);
}
