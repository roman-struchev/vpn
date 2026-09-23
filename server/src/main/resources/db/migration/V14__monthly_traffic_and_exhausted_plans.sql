-- An annual plan got the tariff's monthly quota once, for all 365 days: the
-- UI and landing promise "N GB per month". traffic_reset_at is when the used
-- counter next goes back to zero (every 30 days inside an annual period);
-- NULL for monthly plans, whose renewal is the reset.
ALTER TABLE subscriptions ADD COLUMN traffic_reset_at TIMESTAMPTZ;

-- Low-traffic heads-up (90%) goes out once per traffic period.
ALTER TABLE subscriptions ADD COLUMN traffic_warning_sent_at TIMESTAMPTZ;

-- Annual plans already running: first reset 30 days after the start of the
-- period, stepped forward to the next one still ahead.
UPDATE subscriptions
SET traffic_reset_at = current_period_start
    + INTERVAL '30 days' * (FLOOR(EXTRACT(EPOCH FROM (NOW() - current_period_start)) / (30 * 86400)) + 1)
WHERE is_annual = TRUE
  AND status IN ('ACTIVE', 'EXHAUSTED')
  AND current_period_end > NOW()
  AND tariff_id <> 'trial';
