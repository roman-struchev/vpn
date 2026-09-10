-- Phase 10 hardening (docs/ROADMAP_PROGRESS.md): anti-enumeration.
--
-- 1. GET /api/v1/subscription/export/{userId} took the raw sequential user id
--    with no authentication (see server/.../config/SecurityConfig.java's
--    permitAll on /api/v1/subscription/export/**) — anyone could enumerate
--    userId=1,2,3,... and pull every user's VLESS keys for every node. A
--    subscription URL needs to stay unauthenticated (third-party clients like
--    v2rayNG/Hiddify only support pasting a static URL, not logging in), but
--    the identifier in it must not be guessable. Replace the raw id with an
--    opaque per-user token.
ALTER TABLE users ADD COLUMN subscription_token UUID NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX idx_users_subscription_token ON users(subscription_token);

-- 2. Track subscription-link access by IP so a single account pulling its own
--    node list from an unusually large number of distinct IPs in a short
--    window (PLAN.md §6: "аккаунт тянет ссылку с многих IP") can be detected —
--    that pattern matches someone (e.g. a censor) distributing one paid
--    subscription across many vantage points purely to enumerate the node
--    pool, not a real customer's own devices.
CREATE TABLE subscription_access_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ip_address VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscription_access_log_user_time ON subscription_access_log(user_id, created_at);
