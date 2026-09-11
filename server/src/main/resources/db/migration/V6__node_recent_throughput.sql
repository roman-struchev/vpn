-- Recent per-node throughput, derived from the existing traffic-stats
-- reporting path (NodeManagementService#processTrafficStats): bytes reported
-- since the previous report divided by the elapsed time between reports,
-- giving a lightweight "bytes/sec right now" figure. Used alongside CPU% and
-- active connections as a load signal for the region picker — see
-- SubscriptionExportService#loadLevelFor. last_traffic_stats_at is bookkeeping
-- only (the elapsed-time denominator for the next report); neither column is
-- surfaced to clients directly.
ALTER TABLE nodes ADD COLUMN recent_bytes_per_sec DOUBLE PRECISION;
ALTER TABLE nodes ADD COLUMN last_traffic_stats_at TIMESTAMPTZ;
