-- #248: clean-path settlement of a milestone (milestone-escrow design §7.1). Additive: existing rows keep their state.
--   SETTLING  milestone.settled accepted; the release/refund commands are out, not all confirmed by the bank
--   SETTLED   the bank confirmed every settlement leg (settled_at); escrow moves its view of the money on bank
--             results only, never on its own commands
-- settlement_event_id is the eventId of the milestone.settled that started it: one settlement per milestone.
ALTER TABLE escrow_milestone
    ADD COLUMN settlement_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER last_failure_reason,
    ADD COLUMN settled_at          DATETIME(6) NULL AFTER settlement_event_id,
    DROP CHECK chk_escrow_milestone_status,
    DROP CHECK chk_escrow_milestone_locked;

ALTER TABLE escrow_milestone
    ADD CONSTRAINT chk_escrow_milestone_status CHECK (
        status IN ('FUNDING_PENDING', 'FUNDING_FAILED', 'LOCKED', 'SETTLING', 'SETTLED')),
    -- The batch was locked exactly when the milestone is LOCKED or being / been paid out of the lock.
    ADD CONSTRAINT chk_escrow_milestone_locked CHECK (
        (status IN ('LOCKED', 'SETTLING', 'SETTLED')) = (ledger_entry_id IS NOT NULL AND locked_at IS NOT NULL)),
    ADD CONSTRAINT chk_escrow_milestone_settlement CHECK (
        (status IN ('SETTLING', 'SETTLED')) = (settlement_event_id IS NOT NULL)),
    ADD CONSTRAINT chk_escrow_milestone_settled CHECK ((status = 'SETTLED') = (settled_at IS NOT NULL));

-- One bank command moving locked milestone money out: RELEASE_TO_SELLER (actualAmount) and REFUND_TO_BUYER
-- (lockedAmount - actualAmount). A zero side has no row: zero-value bank commands are invalid. The legs of one
-- settlement add up to the milestone's batch_amount exactly.
--   REQUESTED  command out (first time or a resend), no result yet
--   FAILED     the bank refused it; resent at next_attempt_at with the same request_event_id (D27), NULL = exhausted
--   COMPLETED  the bank confirmed; ledger_entry_id is its ledger row
CREATE TABLE settlement_leg
(
    leg_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contract_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    milestone_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trigger_event_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- the milestone.settled eventId
    entry_type          VARCHAR(30)   NOT NULL,
    fund_type           VARCHAR(20)   NOT NULL,
    user_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount              DECIMAL(18,2) NOT NULL,
    request_event_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                                     -- eventId = sourceEventId of the bank command; a resend reuses it
    status              VARCHAR(20)   NOT NULL,
    ledger_entry_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failed_attempts     INT           NOT NULL DEFAULT 0,
    next_attempt_at     DATETIME(6)   NULL,
    last_failure_reason VARCHAR(500)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,

    PRIMARY KEY (leg_id),
    UNIQUE KEY uk_settlement_leg_request_event (request_event_id),
    UNIQUE KEY uk_settlement_leg_trigger_type (trigger_event_id, entry_type), -- one leg per direction per settlement
    INDEX idx_settlement_leg_milestone (milestone_id),
    INDEX idx_settlement_leg_retry (status, next_attempt_at),
    CONSTRAINT fk_settlement_leg_milestone FOREIGN KEY (milestone_id) REFERENCES escrow_milestone (milestone_id),
    CONSTRAINT chk_settlement_leg_entry_type CHECK (entry_type IN ('RELEASE_TO_SELLER', 'REFUND_TO_BUYER')),
    CONSTRAINT chk_settlement_leg_fund_type CHECK (fund_type = 'MILESTONE_PAYMENT'),
    CONSTRAINT chk_settlement_leg_amount CHECK (amount > 0),
    CONSTRAINT chk_settlement_leg_status CHECK (status IN ('REQUESTED', 'FAILED', 'COMPLETED')),
    CONSTRAINT chk_settlement_leg_completed CHECK ((status = 'COMPLETED') = (ledger_entry_id IS NOT NULL)),
    CONSTRAINT chk_settlement_leg_retry CHECK (next_attempt_at IS NULL OR status = 'FAILED'),
    CONSTRAINT chk_settlement_leg_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT chk_settlement_leg_time CHECK (updated_at >= created_at)
);
