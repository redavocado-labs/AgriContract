-- Preserve the full non-negative integer permitted by the frozen event schema.
-- Existing V6 integer evidence converts losslessly to its canonical decimal form.
ALTER TABLE remedy_lock_evidence
    DROP CHECK chk_remedy_settled_count,
    MODIFY settled_milestone_count LONGTEXT NOT NULL,
    ADD CONSTRAINT chk_remedy_settled_count CHECK (REGEXP_LIKE(settled_milestone_count, '^(0|[1-9][0-9]*)$'));

-- TIMESTAMP ends in 2038 and cannot store historical dates before 1970.
-- Convert its existing instants in UTC; the application also reads/writes UTC DATETIME values.
SET time_zone = '+00:00';
ALTER TABLE lock_entry MODIFY locked_until DATETIME(6) NOT NULL;
ALTER TABLE user_lock_state MODIFY effective_locked_until DATETIME(6) NULL;

-- Shared java-eventing consumer receipt schema, plus a content fingerprint for conflict detection.
CREATE TABLE processed_event (
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_name, event_id)
);
CREATE TABLE remedy_delivery_fingerprint (
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT fk_remedy_delivery_receipt FOREIGN KEY (consumer_name, event_id)
        REFERENCES processed_event(consumer_name, event_id)
);
