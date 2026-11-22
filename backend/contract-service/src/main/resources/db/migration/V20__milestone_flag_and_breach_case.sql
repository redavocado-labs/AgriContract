-- #134, D80/D82: the buyer's flag, the seller's contest (or silence) and the BreachCase allegation it opens.
-- breach_case holds the allegation only; the attribution columns are added by #135.
ALTER TABLE milestone
    ADD COLUMN flag_issue_type  VARCHAR(16)  NULL,
    ADD COLUMN flagged_at       TIMESTAMP(6) NULL,
    ADD COLUMN contested_at     TIMESTAMP(6) NULL,
    ADD COLUMN contested_by     VARCHAR(16)  NULL,
    ADD COLUMN breach_case_id   VARCHAR(36)  NULL;

ALTER TABLE milestone DROP CHECK chk_milestone_settlement;
ALTER TABLE milestone
    ADD CONSTRAINT chk_milestone_settlement CHECK (
        (settlement_path IS NULL) = (billable_quantity_kg IS NULL)
        AND (settlement_path IS NULL) = (seller_amount IS NULL)
        AND (settlement_path IS NULL) = (buyer_refund_amount IS NULL)
        AND (settlement_path IS NULL) = (settled_at IS NULL)
        AND (settlement_path IS NULL OR settlement_path IN ('CLEAN_CONFIRMED', 'CLEAN_AUTO_CONFIRMED',
             'SELLER_SILENT_QUANTITY'))
        AND (seller_amount IS NULL OR (seller_amount >= 0 AND buyer_refund_amount >= 0
             AND seller_amount + buyer_refund_amount = batch_amount)));

CREATE TABLE breach_case (
    breach_case_id          VARCHAR(36)  NOT NULL,
    contract_id             VARCHAR(255) NOT NULL,
    milestone_id            VARCHAR(36)  NULL,
    requested_by            VARCHAR(16)  NOT NULL,
    alleged_breaching_role  VARCHAR(16)  NULL,
    breach_reason_code      VARCHAR(32)  NOT NULL,
    severity                VARCHAR(16)  NOT NULL,
    status                  VARCHAR(32)  NOT NULL,
    evidence_file_ids       JSON         NOT NULL,
    reported_at             TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (breach_case_id),
    CONSTRAINT uk_breach_case_milestone UNIQUE (milestone_id),
    CONSTRAINT fk_breach_case_contract FOREIGN KEY (contract_id) REFERENCES contracts (contract_id)
);
