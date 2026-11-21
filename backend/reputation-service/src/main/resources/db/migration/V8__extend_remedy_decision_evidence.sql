-- V6 did not retain the full decision; a fingerprint cannot be truthfully backfilled.
-- Refuse an upgrade with existing evidence before changing the schema or any rows.
CREATE TEMPORARY TABLE remedy_evidence_upgrade_guard (
    legacy_count BIGINT NOT NULL,
    CONSTRAINT chk_no_legacy_remedy_evidence CHECK (legacy_count = 0)
);
INSERT INTO remedy_evidence_upgrade_guard (legacy_count) SELECT COUNT(*) FROM remedy_lock_evidence;
DROP TEMPORARY TABLE remedy_evidence_upgrade_guard;

-- A final decision can be valid without a lock.
ALTER TABLE remedy_lock_evidence
    DROP FOREIGN KEY fk_remedy_evidence_lock,
    MODIFY breach_reason_code VARCHAR(40) NULL,
    ADD COLUMN decision_fingerprint CHAR(64) NULL;
