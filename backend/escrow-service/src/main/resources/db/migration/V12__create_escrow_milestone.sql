-- #120, D63/D64/D69: escrow's side of one milestone's funding (milestone-escrow design §2.3, §6b).
-- One row per milestone, created by milestone.funding_requested: escrow locks exactly batch_amount (computed and
-- rounded by contract-service, never recomputed here) from the buyer with bank.lock_requested LOCK_MILESTONE.
-- Escrow keeps the state, not a balance: the money lives in bank-service ledger_entry.
--   FUNDING_PENDING  lock sent, or failed with a resend due at next_attempt_at (same request_event_id, D27)
--   FUNDING_FAILED   the last retry failed too; escrow.milestone_funding_failed was written (OPERATOR retry is #239)
--   LOCKED           bank.lock_completed; ledger_entry_id is the bank ledger row, locked_at the bank's lock time
-- Later issues (#248, Level 2) add the release states.
CREATE TABLE escrow_milestone
(
    milestone_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- contract-service milestoneId
    contract_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    milestone_index          INT           NOT NULL,
    buyer_id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_amount             DECIMAL(18,2) NOT NULL,
    funding_requested_at     DATETIME(6)   NOT NULL, -- requestedAt of milestone.funding_requested
    funding_request_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                                     -- eventId of the milestone.funding_requested that created the row
    status                   VARCHAR(30)   NOT NULL,
    request_event_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                                     -- eventId = sourceEventId of bank.lock_requested; a retry resends it
    ledger_entry_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    locked_at                DATETIME(6)   NULL,
    failed_attempts          INT           NOT NULL DEFAULT 0,
    next_attempt_at          DATETIME(6)   NULL,
    last_failure_reason      VARCHAR(500)  NULL,
    created_at               DATETIME(6)   NOT NULL,
    updated_at               DATETIME(6)   NOT NULL,

    PRIMARY KEY (milestone_id),                      -- at most one funding per milestone, whatever the request eventId
    UNIQUE KEY uk_escrow_milestone_request_event (request_event_id),
    INDEX idx_escrow_milestone_contract (contract_id),
    INDEX idx_escrow_milestone_retry (status, next_attempt_at),
    CONSTRAINT chk_escrow_milestone_status CHECK (status IN ('FUNDING_PENDING', 'FUNDING_FAILED', 'LOCKED')),
    CONSTRAINT chk_escrow_milestone_index CHECK (milestone_index BETWEEN 1 AND 100),
    CONSTRAINT chk_escrow_milestone_amount CHECK (batch_amount > 0),
    CONSTRAINT chk_escrow_milestone_locked CHECK (
        (status = 'LOCKED') = (ledger_entry_id IS NOT NULL AND locked_at IS NOT NULL)),
    CONSTRAINT chk_escrow_milestone_retry CHECK (next_attempt_at IS NULL OR status = 'FUNDING_PENDING'),
    CONSTRAINT chk_escrow_milestone_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT chk_escrow_milestone_time CHECK (updated_at >= created_at)
);
