-- Hash chains over the append-only audit record (global + per subject). Additive: nothing is deleted or rewritten.
-- There is no production data and no fake hashes: on a non-empty audit_record the NOT NULL columns fail the
-- migration on purpose. Reset the dev DB before running this.
ALTER TABLE audit_record
    ADD COLUMN global_seq        BIGINT   NOT NULL AUTO_INCREMENT UNIQUE,
    ADD COLUMN record_hash       CHAR(64) NOT NULL,
    ADD COLUMN prev_hash_global  CHAR(64) NULL,
    ADD COLUMN prev_hash_subject CHAR(64) NULL,
    ADD CONSTRAINT uk_audit_record_hash UNIQUE (record_hash),
    ADD CONSTRAINT chk_audit_record_hash CHECK (REGEXP_LIKE(record_hash, '^[a-f0-9]{64}$', 'c')),
    ADD CONSTRAINT chk_audit_record_prev_global
        CHECK (prev_hash_global IS NULL OR REGEXP_LIKE(prev_hash_global, '^[a-f0-9]{64}$', 'c')),
    ADD CONSTRAINT chk_audit_record_prev_subject
        CHECK (prev_hash_subject IS NULL OR REGEXP_LIKE(prev_hash_subject, '^[a-f0-9]{64}$', 'c')),
    ADD INDEX idx_audit_record_subject_seq (subject_type, subject_id, global_seq);

-- Single-row mutex that serialises chained inserts. MySQL's SELECT ... FOR UPDATE needs UPDATE privilege, and the
-- runtime account must never have UPDATE on audit_record, so the lock lives on its own table (ops/grants.sql).
CREATE TABLE audit_chain_lock (
    lock_id TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_audit_chain_lock CHECK (lock_id = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO audit_chain_lock (lock_id) VALUES (1);
