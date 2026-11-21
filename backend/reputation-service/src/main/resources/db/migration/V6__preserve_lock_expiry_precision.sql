-- D66 expiry is decided_at plus whole days; retain the same microsecond precision.
ALTER TABLE lock_entry MODIFY locked_until TIMESTAMP(6) NOT NULL;
ALTER TABLE user_lock_state MODIFY effective_locked_until TIMESTAMP(6) NULL;

-- Preserve decision inputs needed to distinguish replay from conflicting reuse, including expired locks.
CREATE TABLE remedy_lock_evidence (
    remedy_decision_id CHAR(36) PRIMARY KEY,
    buyer_id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    breach_reason_code VARCHAR(40) NOT NULL,
    settled_milestone_count INT NOT NULL,
    CONSTRAINT chk_remedy_settled_count CHECK (settled_milestone_count >= 0),
    CONSTRAINT fk_remedy_evidence_lock FOREIGN KEY (remedy_decision_id) REFERENCES lock_entry(remedy_decision_id)
);

-- A second source event can replay the same decision; retain its identity for conflict checks.
CREATE TABLE remedy_event_identity (
    source_event_id CHAR(36) PRIMARY KEY,
    remedy_decision_id CHAR(36) NOT NULL,
    CONSTRAINT fk_remedy_event_evidence FOREIGN KEY (remedy_decision_id) REFERENCES remedy_lock_evidence(remedy_decision_id)
);
