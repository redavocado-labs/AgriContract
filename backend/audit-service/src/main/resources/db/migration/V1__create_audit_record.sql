-- Append-only audit record. Rows are never updated or deleted: the runtime account only has SELECT, INSERT
-- (ops/grants.sql). source_event_id UNIQUE is the dedup key: the record is its own inbox entry.
CREATE TABLE audit_record (
    record_id         CHAR(36)     NOT NULL PRIMARY KEY,
    subject_type      VARCHAR(20)  NOT NULL,
    subject_id        VARCHAR(100) NOT NULL,
    source_type       VARCHAR(50)  NOT NULL,
    source_event_type VARCHAR(100) NOT NULL,
    source_event_id   CHAR(36)     NOT NULL,
    causation_id      VARCHAR(64)  NULL,
    correlation_id    VARCHAR(64)  NULL,
    source_hash       VARCHAR(64)  NULL,
    occurred_at       TIMESTAMP(6) NOT NULL,
    content           JSON         NOT NULL,
    created_at        TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_audit_record_source_event UNIQUE (source_event_id),
    CONSTRAINT chk_audit_record_subject_type CHECK (subject_type IN ('CONTRACT', 'USER_PAIR', 'SYSTEM')),
    CONSTRAINT chk_audit_record_source_hash CHECK (source_hash IS NULL OR REGEXP_LIKE(source_hash, '^[a-f0-9]{64}$', 'c')),
    INDEX idx_audit_record_subject (subject_type, subject_id, created_at),
    INDEX idx_audit_record_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
