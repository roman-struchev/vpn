-- Fleet-wide error collection: every node agent and client app reports the
-- failures it hits, so they can be looked at together instead of only being
-- visible to whoever happens to read one machine's logs at the right moment.
-- (Until now the only thing crossing the wire was conn_telemetry, which
-- counts connect successes/failures and says nothing about *what* broke.)
--
-- Stored AGGREGATED, one row per distinct issue rather than per occurrence:
-- `fingerprint` is a hash of source+component+code+the message with its
-- variable parts masked (DiagnosticsService#fingerprintOf), so a thousand
-- copies of the same crash cost one row with occurrences=1000 instead of a
-- thousand rows. That is what keeps this table bounded under an incident —
-- exactly when a per-occurrence log would explode — and it is also the shape
-- an analysis wants to read ("which issues, how often, since when, on which
-- versions"), rather than a flat firehose it would have to group itself.
--
-- Bounded further by DiagnosticsPruneTask: rows older than the retention
-- window are deleted, and the table is capped at a maximum number of rows
-- (least-recently-seen evicted first).
CREATE TABLE diagnostic_events (
    id BIGSERIAL PRIMARY KEY,

    -- sha256(source|component|code|normalized message), hex-truncated.
    fingerprint VARCHAR(64) NOT NULL UNIQUE,

    -- Where it happened: NODE_AGENT, ANDROID, DESKTOP, WEB, SERVER.
    source VARCHAR(16) NOT NULL,
    -- ERROR or WARN. Anything lower is not worth shipping off the machine.
    severity VARCHAR(8) NOT NULL DEFAULT 'ERROR',
    -- Subsystem inside that source, e.g. 'xray', 'tunnel', 'api', 'relay'.
    component VARCHAR(64) NOT NULL DEFAULT 'unknown',
    -- Stable machine-readable label, e.g. 'XRAY_EXITED', 'TUNNEL_START_FAILED'.
    code VARCHAR(64) NOT NULL DEFAULT 'UNSPECIFIED',

    -- The masked message the fingerprint was taken over (no ids/IPs left in it).
    message VARCHAR(512) NOT NULL,
    -- One sample of the raw extra detail (stack trace, command output), kept
    -- from the most recent occurrence only — the aggregate would otherwise
    -- have nothing concrete to look at.
    sample_detail TEXT,
    -- Small JSON object of the latest occurrence's context (app version, OS,
    -- transport, region...). Capped in size by the service, not by the DB.
    sample_context TEXT,

    -- Which app builds this issue has been seen on, newest last. Lets an
    -- analysis say "only on 0.1.12" without keeping a row per occurrence.
    app_versions TEXT,

    -- Best-effort attribution; both are nullable and deliberately ON DELETE
    -- SET NULL, so pruning a p2p node or deleting a user never blocks on, or
    -- silently drops, its error history.
    last_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    last_node_id BIGINT REFERENCES nodes(id) ON DELETE SET NULL,

    occurrences BIGINT NOT NULL DEFAULT 1,
    -- Distinct reporters, counted approximately (incremented when a report
    -- arrives from a reporter id not equal to the last one seen) — enough to
    -- tell "one machine in a loop" from "everybody's app".
    reporters BIGINT NOT NULL DEFAULT 1,
    last_reporter VARCHAR(64),

    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The two access paths: "what happened recently" (both the summary endpoint
-- and the retention prune) and "cap the table" / "group by source".
CREATE INDEX idx_diagnostic_events_last_seen ON diagnostic_events(last_seen_at DESC);
CREATE INDEX idx_diagnostic_events_source_last_seen ON diagnostic_events(source, last_seen_at DESC);
