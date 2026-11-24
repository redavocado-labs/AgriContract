-- #609 (D93): escrow tells contract-service when a contract's money is all moved (escrow.contract_lock_cleared).
-- One row per contract whose remedy or settlement leg turned COMPLETED. ContractClearanceScheduler checks every row
-- with checked_at NULL: no remedy leg SENT or FAILED and a bank-ledger remaining lock of exactly 0.00 publishes the
-- event. A new completion sets requested_at again and checked_at back to NULL, so the contract is checked again.
-- requested_at is compared before the result is committed: a completion landing during a check is never lost.
CREATE TABLE contract_clearance_check
(
    contract_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    checked_at   TIMESTAMP(6) NULL,

    PRIMARY KEY (contract_id),
    INDEX idx_contract_clearance_unchecked (checked_at, requested_at)
);

-- The payee of the leg: role of the remedyLegs[] item of remedy.finalized (D86), for the operator alert
-- (notification.escrow_remedy_leg_alert_requested party). NULL on rows written before #609: derived from entry_type
-- and fund_type where only one payee is possible.
ALTER TABLE remedy_leg
    ADD COLUMN payee_role VARCHAR(10) NULL AFTER user_id,
    ADD CONSTRAINT chk_remedy_leg_payee_role CHECK (payee_role IS NULL OR payee_role IN ('BUYER', 'SELLER'));
