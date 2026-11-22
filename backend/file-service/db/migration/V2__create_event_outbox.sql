-- Transactional outbox of @agricontract/eventing (backend/shared/node-eventing/migrations/V1__create_eventing_tables.sql),
-- additive. The row change and its event commit together; OutboxPoller publishes committed rows to agricontract.files
-- (routing key = eventType): the scan job file.uploaded.direct (design §4) and file.ready / file.failed (§5).
-- processed_event of the same file is left out: no consumer here dedupes by eventId (the scan is idempotent on the
-- file's status), so the table would stay empty.
CREATE TABLE IF NOT EXISTS event_outbox (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    event_id     CHAR(36)      NOT NULL,
    event_type   VARCHAR(100)  NOT NULL,
    aggregate_id VARCHAR(255)  NOT NULL,
    envelope     JSON          NOT NULL,
    status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts     INT           NOT NULL DEFAULT 0,
    last_error   VARCHAR(1000) NULL,
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_outbox_event_id (event_id),
    INDEX idx_event_outbox_status_id (status, id)
);
