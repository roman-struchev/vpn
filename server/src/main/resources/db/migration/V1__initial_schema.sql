-- =============================================================================
-- V1 Initial Schema: Core tables for VPN Service
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. USERS
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255),
    telegram_id BIGINT UNIQUE,
    google_sub VARCHAR(255) UNIQUE,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    balance_usdt_micro BIGINT NOT NULL DEFAULT 0,
    referral_code VARCHAR(32) NOT NULL UNIQUE,
    referred_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_telegram_id ON users(telegram_id);
CREATE INDEX idx_users_referral_code ON users(referral_code);

-- 2. TARIFFS
CREATE TABLE tariffs (
    id VARCHAR(32) PRIMARY KEY, -- 'trial', 'basic', 'pro'
    name VARCHAR(64) NOT NULL,
    monthly_price_usdt_micro BIGINT NOT NULL,
    annual_price_usdt_micro BIGINT NOT NULL,
    traffic_quota_bytes BIGINT NOT NULL,
    max_devices INT NOT NULL,
    server_pool VARCHAR(32) NOT NULL DEFAULT 'paid', -- 'trial', 'paid'
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed initial tariffs according to PLAN.md §2
INSERT INTO tariffs (id, name, monthly_price_usdt_micro, annual_price_usdt_micro, traffic_quota_bytes, max_devices, server_pool)
VALUES 
    ('trial', 'Пробный', 0, 0, 1073741824, 1, 'trial'), -- 1 GB, 1 device
    ('basic', 'Basic', 1000000, 9600000, 21474836480, 2, 'paid'), -- $1 / $9.6, 20 GB, 2 devices
    ('pro', 'Pro', 2000000, 19200000, 107374182400, 5, 'paid'); -- $2 / $19.2, 100 GB, 5 devices

-- 3. SUBSCRIPTIONS
CREATE TABLE subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tariff_id VARCHAR(32) NOT NULL REFERENCES tariffs(id),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, EXPIRED, CANCELLED
    is_annual BOOLEAN NOT NULL DEFAULT FALSE,
    auto_renew BOOLEAN NOT NULL DEFAULT TRUE,
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end TIMESTAMPTZ NOT NULL,
    traffic_used_bytes BIGINT NOT NULL DEFAULT 0,
    traffic_limit_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscriptions_user_status ON subscriptions(user_id, status);

-- 4. DEVICES
CREATE TABLE devices (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name VARCHAR(128) NOT NULL,
    platform VARCHAR(32) NOT NULL, -- ANDROID, WINDOWS, MACOS, THIRD_PARTY
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ
);

CREATE INDEX idx_devices_user_id ON devices(user_id);

-- 5. NODES
CREATE TABLE nodes (
    id BIGSERIAL PRIMARY KEY,
    hostname VARCHAR(255) NOT NULL UNIQUE,
    public_ip VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OFFLINE', -- ONLINE, OFFLINE, DRAINING, MAINTENANCE
    pool VARCHAR(32) NOT NULL DEFAULT 'paid', -- trial, paid, quarantine
    type VARCHAR(32) NOT NULL DEFAULT 'direct', -- direct (XHTTP+Reality), cdn (XHTTP behind CDN)
    region VARCHAR(64) NOT NULL,
    asn VARCHAR(64),
    reality_public_key VARCHAR(128),
    reality_short_ids TEXT[],
    current_config_hash VARCHAR(64),
    config_version BIGINT NOT NULL DEFAULT 1,
    cpu_percent NUMERIC(5,2),
    memory_used_bytes BIGINT,
    memory_total_bytes BIGINT,
    active_connections INT DEFAULT 0,
    last_heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_nodes_pool_status ON nodes(pool, status);

-- 6. NODE BOOTSTRAP TOKENS & CREDENTIALS
CREATE TABLE node_bootstrap_tokens (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(128) NOT NULL UNIQUE,
    assigned_pool VARCHAR(32) NOT NULL DEFAULT 'paid',
    assigned_type VARCHAR(32) NOT NULL DEFAULT 'direct',
    is_used BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMPTZ,
    used_by_node_id BIGINT REFERENCES nodes(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE node_credentials (
    id BIGSERIAL PRIMARY KEY,
    node_id BIGINT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_node_credentials_node_id ON node_credentials(node_id);

-- 7. DEVICE NODE KEYS (UUID per (device, user, node) tuple)
CREATE TABLE device_node_keys (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    node_id BIGINT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    uuid UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_device_node UNIQUE (device_id, node_id)
);

CREATE INDEX idx_device_node_keys_node_id ON device_node_keys(node_id);
CREATE INDEX idx_device_node_keys_uuid ON device_node_keys(uuid);

-- 8. BALANCE AUDIT ENTRIES
CREATE TABLE balance_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount_usdt_micro BIGINT NOT NULL, -- positive for deposit/credit, negative for subscription/debit
    balance_after_micro BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL, -- DEPOSIT, SUBSCRIPTION_DEBIT, REFUND, REFERRAL_BONUS, MANUAL_ADJUSTMENT
    description TEXT NOT NULL,
    reference_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_balance_entries_user_id ON balance_entries(user_id);

-- 9. CRYPTO INVOICES
CREATE TABLE crypto_invoices (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chain VARCHAR(32) NOT NULL, -- TRON, ETHEREUM, BASE, ARBITRUM
    token VARCHAR(32) NOT NULL DEFAULT 'USDT',
    base_amount_usdt_micro BIGINT NOT NULL,
    delta_step_micro INT NOT NULL, -- random k in [1..999] * 1000 micro (0.001 USDT)
    expected_amount_usdt_micro BIGINT NOT NULL,
    tolerance_min_micro BIGINT NOT NULL, -- expected - 400 micro
    tolerance_max_micro BIGINT NOT NULL, -- expected + 400 micro
    recipient_address VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, PAID, EXPIRED, CANCELLED
    tx_hash VARCHAR(255),
    actual_amount_usdt_micro BIGINT,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_at TIMESTAMPTZ
);

CREATE INDEX idx_crypto_invoices_user_id ON crypto_invoices(user_id);
CREATE INDEX idx_crypto_invoices_status ON crypto_invoices(status);
CREATE INDEX idx_crypto_invoices_expected ON crypto_invoices(chain, expected_amount_usdt_micro) WHERE status = 'PENDING';

-- 10. TELEMETRY & CONNECTION QUALITY
CREATE TABLE conn_telemetry (
    id BIGSERIAL PRIMARY KEY,
    node_id BIGINT REFERENCES nodes(id) ON DELETE SET NULL,
    operator VARCHAR(64) NOT NULL,
    region VARCHAR(64) NOT NULL,
    transport VARCHAR(32) NOT NULL,
    connect_time_ms INT NOT NULL,
    failure_count INT NOT NULL DEFAULT 0,
    is_whitelist_suspected BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conn_telemetry_analytics ON conn_telemetry(operator, region, transport, created_at);

-- 11. TRANSPORT POLICIES
CREATE TABLE transport_policies (
    id BIGSERIAL PRIMARY KEY,
    scope VARCHAR(32) NOT NULL, -- global, region, operator, user
    scope_value VARCHAR(128) NOT NULL, -- '*', 'RU-MOW', 'MTS', etc.
    primary_transport VARCHAR(32) NOT NULL DEFAULT 'XHTTP',
    fallback_transport VARCHAR(32) NOT NULL DEFAULT 'GRPC',
    fingerprint VARCHAR(32) NOT NULL DEFAULT 'firefox',
    backoff_initial_sec INT NOT NULL DEFAULT 15,
    max_retries_before_node_switch INT NOT NULL DEFAULT 3,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transport_policies_lookup ON transport_policies(scope, scope_value, is_active);

-- Default global transport policy according to PLAN.md §6
INSERT INTO transport_policies (scope, scope_value, primary_transport, fallback_transport, fingerprint, backoff_initial_sec, max_retries_before_node_switch)
VALUES ('global', '*', 'XHTTP', 'GRPC', 'firefox', 15, 3);
