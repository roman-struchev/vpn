-- CPU core count (so the admin UI can show "23% (4 cores)" — cpu_percent
-- is already normalized per-core, but the raw percentage alone left it
-- unclear how many cores it was relative to) and cumulative traffic served,
-- for per-node visibility in the admin panel alongside active_connections.
ALTER TABLE nodes ADD COLUMN cpu_count INT;
ALTER TABLE nodes ADD COLUMN total_bytes_served BIGINT NOT NULL DEFAULT 0;
