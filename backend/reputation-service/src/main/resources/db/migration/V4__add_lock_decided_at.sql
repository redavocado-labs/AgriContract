-- D66: never infer decision time for an existing immutable lock.
-- The temporary CHECK fails before any persistent schema change if legacy rows exist.
CREATE TEMPORARY TABLE lock_decision_migration_guard (
    legacy_count BIGINT NOT NULL,
    CONSTRAINT chk_empty_lock_ledger CHECK (legacy_count = 0)
);
INSERT INTO lock_decision_migration_guard (legacy_count) SELECT COUNT(*) FROM lock_entry;
DROP TEMPORARY TABLE lock_decision_migration_guard;

ALTER TABLE lock_entry ADD COLUMN decided_at DATETIME(6) NOT NULL;
CREATE INDEX idx_lock_user_decided ON lock_entry(user_id, decided_at);
