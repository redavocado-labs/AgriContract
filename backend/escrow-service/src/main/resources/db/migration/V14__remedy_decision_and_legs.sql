-- #249: remedy.finalized (milestone-escrow design §6.7). One row per remedyDecisionId escrow has looked at, and one
-- row per bank command it dispatched for that decision.
--   remedy_decision  ACCEPTED: the legs below were written with their commands, in the same transaction.
--                    REJECTED: the leg set broke the mapping table, the scope rule or the conservation check against
--                    the bank ledger; nothing was written but this row (reject_reason), so a replay is ignored.
-- remedy_decision_id is the idempotency key: a second event for it never dispatches again.
CREATE TABLE remedy_decision
(
    remedy_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contract_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- the remedy.finalized eventId
    status             VARCHAR(20)  NOT NULL,
    reject_reason      VARCHAR(500) NULL,
    created_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (remedy_decision_id),
    INDEX idx_remedy_decision_contract (contract_id),
    CONSTRAINT chk_remedy_decision_status CHECK (status IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT chk_remedy_decision_reason CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
);

-- One bank command of an accepted decision. A zero-amount or NONE leg has no row.
--   SENT       command out (first time or a resend), no result yet
--   FAILED     the bank refused it; resent at next_attempt_at with the same request_event_id (D27), NULL = exhausted
--   COMPLETED  the bank confirmed; ledger_entry_id is its ledger row
CREATE TABLE remedy_leg
(
    remedy_leg_id      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    remedy_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contract_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    milestone_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,     -- MILESTONE_PAYMENT legs only
    remedy_type        VARCHAR(30)   NOT NULL,
    entry_type         VARCHAR(30)   NOT NULL,
    fund_type          VARCHAR(20)   NOT NULL,
    user_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- who receives the money (D86)
    amount             DECIMAL(18,2) NOT NULL,
    request_event_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                                   -- eventId = sourceEventId of the bank command; a resend reuses it
    status             VARCHAR(20)   NOT NULL,
    attempts           INT           NOT NULL DEFAULT 0,
    next_attempt_at    DATETIME(6)   NULL,
    failure_reason     VARCHAR(500)  NULL,
    ledger_entry_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,

    PRIMARY KEY (remedy_leg_id),
    UNIQUE KEY uk_remedy_leg_request_event (request_event_id),
    INDEX idx_remedy_leg_decision (remedy_decision_id),
    INDEX idx_remedy_leg_contract (contract_id, status),
    INDEX idx_remedy_leg_retry (status, next_attempt_at),
    CONSTRAINT fk_remedy_leg_decision FOREIGN KEY (remedy_decision_id) REFERENCES remedy_decision (remedy_decision_id),
    CONSTRAINT chk_remedy_leg_type CHECK (remedy_type IN (
        'PAYMENT_SETTLEMENT', 'PAYMENT_REFUND', 'DEPOSIT_FORFEITURE', 'CONTRACTUAL_PENALTY', 'DAMAGES_COMPENSATION')),
    CONSTRAINT chk_remedy_leg_entry_type CHECK (entry_type IN (
        'RELEASE_TO_SELLER', 'REFUND_TO_BUYER', 'DEPOSIT_FORFEITURE', 'CONTRACTUAL_PENALTY', 'DAMAGES_COMPENSATION')),
    CONSTRAINT chk_remedy_leg_fund_type CHECK (fund_type IN ('BUYER_DEPOSIT', 'SELLER_DEPOSIT', 'MILESTONE_PAYMENT')),
    CONSTRAINT chk_remedy_leg_amount CHECK (amount > 0),
    CONSTRAINT chk_remedy_leg_status CHECK (status IN ('SENT', 'FAILED', 'COMPLETED')),
    CONSTRAINT chk_remedy_leg_completed CHECK ((status = 'COMPLETED') = (ledger_entry_id IS NOT NULL)),
    CONSTRAINT chk_remedy_leg_retry CHECK (next_attempt_at IS NULL OR status = 'FAILED'),
    CONSTRAINT chk_remedy_leg_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_remedy_leg_time CHECK (updated_at >= created_at)
);
