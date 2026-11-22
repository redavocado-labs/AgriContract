-- #137, D81/D86: the RemedyDecision of a resolved milestone dispute and its money legs. Both tables are insert once:
-- the only update is remedy_decision.status PENDING_CONTEST -> FINALIZED with finalized_at. breach_case_id is NULL
-- only for a system-decided remedy (#240, not built yet); one decision per case. remedy_leg_id is the dedup key
-- escrow-service uses. penalty_debt_amount is evidence of the part of the penalty the seller deposit could not
-- cover: it has no leg and is not collected.
CREATE TABLE remedy_decision (
    remedy_decision_id    VARCHAR(36)    NOT NULL,
    contract_id           VARCHAR(255)   NOT NULL,
    breach_case_id        VARCHAR(36)    NULL,
    milestone_id          VARCHAR(36)    NOT NULL,
    status                VARCHAR(16)    NOT NULL,
    final_breaching_role  VARCHAR(16)    NULL,
    breach_reason_code    VARCHAR(32)    NULL,
    quality_disposition   VARCHAR(24)    NULL,
    decision_source       VARCHAR(16)    NOT NULL,
    penalty_amount        DECIMAL(19, 2) NOT NULL,
    penalty_debt_amount   DECIMAL(19, 2) NOT NULL,
    settled_milestone_count INT          NOT NULL,
    created_at            TIMESTAMP(6)   NOT NULL,
    finalized_at          TIMESTAMP(6)   NULL,
    PRIMARY KEY (remedy_decision_id),
    CONSTRAINT uk_remedy_decision_breach_case UNIQUE (breach_case_id),
    CONSTRAINT fk_remedy_decision_contract FOREIGN KEY (contract_id) REFERENCES contracts (contract_id),
    CONSTRAINT fk_remedy_decision_breach_case FOREIGN KEY (breach_case_id) REFERENCES breach_case (breach_case_id),
    CONSTRAINT fk_remedy_decision_milestone FOREIGN KEY (milestone_id) REFERENCES milestone (milestone_id),
    CONSTRAINT chk_remedy_decision_status CHECK (status IN ('PENDING_CONTEST', 'FINALIZED')),
    CONSTRAINT chk_remedy_decision_finalized CHECK ((status = 'FINALIZED') = (finalized_at IS NOT NULL)),
    CONSTRAINT chk_remedy_decision_money CHECK (penalty_amount >= 0 AND penalty_debt_amount >= 0
        AND penalty_debt_amount <= penalty_amount),
    CONSTRAINT chk_remedy_decision_settled_count CHECK (settled_milestone_count >= 0)
);

CREATE INDEX idx_remedy_decision_contract ON remedy_decision (contract_id);

CREATE TABLE remedy_leg (
    remedy_leg_id      VARCHAR(36)    NOT NULL,
    remedy_decision_id VARCHAR(36)    NOT NULL,
    seq                INT            NOT NULL,
    remedy_type        VARCHAR(24)    NOT NULL,
    fund_type          VARCHAR(24)    NOT NULL,
    role               VARCHAR(16)    NOT NULL,
    amount             DECIMAL(19, 2) NOT NULL,
    PRIMARY KEY (remedy_leg_id),
    CONSTRAINT uk_remedy_leg_seq UNIQUE (remedy_decision_id, seq),
    CONSTRAINT fk_remedy_leg_decision FOREIGN KEY (remedy_decision_id) REFERENCES remedy_decision (remedy_decision_id),
    CONSTRAINT chk_remedy_leg_amount CHECK (amount > 0),
    CONSTRAINT chk_remedy_leg_role CHECK (role IN ('BUYER', 'SELLER'))
);

-- A CONTESTED milestone settled by its remedy (DISPUTE_RESOLVED).
ALTER TABLE milestone DROP CHECK chk_milestone_settlement;
ALTER TABLE milestone
    ADD CONSTRAINT chk_milestone_settlement CHECK (
        (settlement_path IS NULL) = (billable_quantity_kg IS NULL)
        AND (settlement_path IS NULL) = (seller_amount IS NULL)
        AND (settlement_path IS NULL) = (buyer_refund_amount IS NULL)
        AND (settlement_path IS NULL) = (settled_at IS NULL)
        AND (settlement_path IS NULL OR settlement_path IN ('CLEAN_CONFIRMED', 'CLEAN_AUTO_CONFIRMED',
             'SELLER_SILENT_QUANTITY', 'INSPECTED_CONFORMING', 'DISPUTE_RESOLVED'))
        AND (seller_amount IS NULL OR (seller_amount >= 0 AND buyer_refund_amount >= 0
             AND seller_amount + buyer_refund_amount = batch_amount)));
