-- Bootstrap tokens: was strictly single-use (registerNode looked up
-- "token AND is_used = false" and rejected a second install with the same
-- token). Product ask: let ops provision several VPS with one token during
-- its validity window instead of minting a fresh one per host. is_used/
-- used_at/used_by_node_id are kept as "has this token ever been used /
-- most recent use" for admin visibility; use_count is the new source of
-- truth for "how many nodes registered with this token".
ALTER TABLE node_bootstrap_tokens ADD COLUMN use_count INT NOT NULL DEFAULT 0;

-- Temporary tariff override on a subscription: lets an admin grant a user a
-- different tariff for a fixed period (e.g. compensation, promo, support
-- goodwill) without touching their real billing tariff/auto-renew. While
-- override_expires_at is in the future, Subscription#getEffectiveTariff()
-- returns override_tariff_id instead of tariff_id; QuotaEnforcementTask
-- clears the three columns once it's past and restores traffic_limit_bytes
-- from the snapshot taken when the override was granted.
ALTER TABLE subscriptions ADD COLUMN override_tariff_id VARCHAR(32) REFERENCES tariffs(id);
ALTER TABLE subscriptions ADD COLUMN override_expires_at TIMESTAMPTZ;
ALTER TABLE subscriptions ADD COLUMN override_previous_traffic_limit_bytes BIGINT;
