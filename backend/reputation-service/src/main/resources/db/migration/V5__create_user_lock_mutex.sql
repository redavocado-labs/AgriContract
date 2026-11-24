-- Serializes all lock lifecycle operations without requiring a projection row.
CREATE TABLE user_lock_mutex (
    user_id CHAR(36) PRIMARY KEY
);

-- Existing TIMESTAMP instants are interpreted in UTC before conversion.
SET time_zone = '+00:00';
ALTER TABLE lock_entry MODIFY locked_until DATETIME(6) NOT NULL;
ALTER TABLE user_lock_state MODIFY effective_locked_until DATETIME(6) NULL;

CREATE TABLE remedy_lock_evidence (
    remedy_decision_id CHAR(36) PRIMARY KEY,
    buyer_id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    breach_reason_code VARCHAR(40) NULL,
    settled_milestone_count INT NOT NULL,
    decision_fingerprint CHAR(64) NOT NULL,
    CONSTRAINT chk_remedy_settled_count CHECK (settled_milestone_count >= 0)
);
CREATE TABLE remedy_event_identity (
    source_event_id CHAR(36) PRIMARY KEY,
    remedy_decision_id CHAR(36) NOT NULL,
    CONSTRAINT fk_remedy_event_evidence FOREIGN KEY (remedy_decision_id) REFERENCES remedy_lock_evidence(remedy_decision_id)
);
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
