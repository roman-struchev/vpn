-- Backs the desktop app's no-signup trial flow (DeviceAuthService): a fresh
-- install generates a local device UUID and logs in with it, finding-or-
-- creating a User keyed by this column instead of requiring email/password
-- or Telegram up front. Nullable + unique, same convention as telegram_id
-- and google_sub.
ALTER TABLE users ADD COLUMN device_uuid VARCHAR(255) UNIQUE;
