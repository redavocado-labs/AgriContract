-- #494, D57/D92: a milestone dispute ended by a concession or an agreed settlement. All additive.

-- 1. The OTP of a dispute exit confirms CONCEDE_DISPUTE or SETTLE_DISPUTE. dispute_exit_otp binds the code to the
--    milestone and, for a settlement, to the amount the party consented to at initiate; verify uses only these.
ALTER TABLE signature_otp DROP CHECK chk_signature_otp_action;
ALTER TABLE signature_otp
    ADD CONSTRAINT chk_signature_otp_action CHECK (action_type IN ('SIGN_CONTRACT', 'CANCEL_ACTIVATION',
        'CONCEDE_DISPUTE', 'SETTLE_DISPUTE'));

CREATE TABLE dispute_exit_otp (
    otp_id                           CHAR(36)       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    milestone_id                     VARCHAR(36)    NOT NULL,
    exit_type                        VARCHAR(16)    NOT NULL,
    agreed_seller_entitlement_amount DECIMAL(19, 2) NULL,
    PRIMARY KEY (otp_id),
    CONSTRAINT fk_dispute_exit_otp_otp FOREIGN KEY (otp_id) REFERENCES signature_otp (otp_id),
    CONSTRAINT fk_dispute_exit_otp_milestone FOREIGN KEY (milestone_id) REFERENCES milestone (milestone_id),
    CONSTRAINT chk_dispute_exit_otp_type CHECK (exit_type IN ('CONCEDE', 'SETTLE')),
    CONSTRAINT chk_dispute_exit_otp_amount CHECK ((exit_type = 'SETTLE') = (agreed_seller_entitlement_amount IS NOT NULL)
        AND (agreed_seller_entitlement_amount IS NULL OR agreed_seller_entitlement_amount >= 0))
);

-- 2. Settlement proposals: at most one OPEN per milestone (generated column + unique, like
--    activation_cancel_request). A proposal is never deleted; it ends WITHDRAWN, REFUSED, ACCEPTED or LAPSED.
CREATE TABLE dispute_settlement_proposal (
    proposal_id                      CHAR(36)       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contract_id                      VARCHAR(255)   NOT NULL,
    milestone_id                     VARCHAR(36)    NOT NULL,
    agreed_seller_entitlement_amount DECIMAL(19, 2) NOT NULL,
    proposed_by_role                 VARCHAR(8)     NOT NULL,
    proposed_by_actor_id             VARCHAR(36)    NOT NULL,
    proposed_at                      TIMESTAMP(6)   NOT NULL,
    status                           VARCHAR(16)    NOT NULL,
    ended_at                         TIMESTAMP(6)   NULL,
    ended_by_actor_id                VARCHAR(36)    NULL,
    open_milestone_id                VARCHAR(36)    GENERATED ALWAYS AS (IF(status = 'OPEN', milestone_id, NULL)) STORED,
    PRIMARY KEY (proposal_id),
    CONSTRAINT uq_dispute_settlement_open UNIQUE (open_milestone_id),
    CONSTRAINT fk_dispute_settlement_contract FOREIGN KEY (contract_id) REFERENCES contracts (contract_id),
    CONSTRAINT fk_dispute_settlement_milestone FOREIGN KEY (milestone_id) REFERENCES milestone (milestone_id),
    CONSTRAINT chk_dispute_settlement_role CHECK (proposed_by_role IN ('BUYER', 'SELLER')),
    CONSTRAINT chk_dispute_settlement_status CHECK (status IN ('OPEN', 'WITHDRAWN', 'REFUSED', 'ACCEPTED', 'LAPSED')),
    CONSTRAINT chk_dispute_settlement_ended CHECK ((status = 'OPEN') = (ended_at IS NULL)),
    CONSTRAINT chk_dispute_settlement_amount CHECK (agreed_seller_entitlement_amount >= 0)
);

CREATE INDEX idx_dispute_settlement_milestone ON dispute_settlement_proposal (milestone_id, proposed_at);

-- 3. Every inspection.level1_5_requested contract-service writes, and how it ended: REPORTED when a report for the
--    milestone was stored, CANCELLED when the dispute ended first (inspection.level1_5_cancelled).
CREATE TABLE level1_5_request (
    request_id             CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    milestone_id           VARCHAR(36)  NOT NULL,
    reason                 VARCHAR(32)  NOT NULL,
    initiated_by_actor_id  VARCHAR(36)  NULL,
    requested_at           TIMESTAMP(6) NOT NULL,
    ended_at               TIMESTAMP(6) NULL,
    end_reason             VARCHAR(16)  NULL,
    PRIMARY KEY (request_id),
    KEY idx_level1_5_request_milestone (milestone_id, ended_at),
    CONSTRAINT fk_level1_5_request_milestone FOREIGN KEY (milestone_id) REFERENCES milestone (milestone_id),
    CONSTRAINT chk_level1_5_request_end CHECK ((ended_at IS NULL) = (end_reason IS NULL)
        AND (end_reason IS NULL OR end_reason IN ('REPORTED', 'CANCELLED')))
);

-- 4. inspection.level1_5_assigned: one row per assignment (first pick or re-pick); the latest of a milestone is the
--    active one. free_cancel_until decides who pays the inspection when a dispute ends before the report.
CREATE TABLE level1_5_assignment (
    commission_id      VARCHAR(36)  NOT NULL,
    milestone_id       VARCHAR(36)  NOT NULL,
    assigned_at        TIMESTAMP(6) NOT NULL,
    free_cancel_until  TIMESTAMP(6) NOT NULL,
    report_due_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (commission_id),
    KEY idx_level1_5_assignment_milestone (milestone_id, assigned_at),
    CONSTRAINT fk_level1_5_assignment_milestone FOREIGN KEY (milestone_id) REFERENCES milestone (milestone_id)
);

-- 5. Who bears the Level 1.5 inspection fee of a decision: the non-prevailing role of a report-based decision; for a
--    dispute exit the conceding party, the requester or SPLIT (D92). NULL = no fee. Evidence only, no ledger leg.
ALTER TABLE remedy_decision
    ADD COLUMN inspection_fee_party VARCHAR(8) NULL,
    ADD CONSTRAINT chk_remedy_decision_fee_party CHECK (inspection_fee_party IS NULL
        OR inspection_fee_party IN ('BUYER', 'SELLER', 'SPLIT'));
