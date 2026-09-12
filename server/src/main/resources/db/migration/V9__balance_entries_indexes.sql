-- V9: Performance indexes for balance entries audit & reconciliation
CREATE INDEX IF NOT EXISTS idx_balance_entries_reference_id ON balance_entries(reference_id);
CREATE INDEX IF NOT EXISTS idx_balance_entries_user_type ON balance_entries(user_id, type);
