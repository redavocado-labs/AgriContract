-- #238, D94: the normal completion of a contract is a SYSTEM no-fault RemedyDecision that returns the deposits. It is
-- on the whole contract, so it has no milestone and no breach case. A decision on a breach case still names its
-- milestone. At most one contract-level decision per contract: contract_level_key is the contract id only for a
-- decision with neither a milestone nor a breach case, and NULL (not unique-checked) otherwise. All additive.
ALTER TABLE remedy_decision
    MODIFY COLUMN milestone_id VARCHAR(36) NULL,
    ADD COLUMN contract_level_key VARCHAR(255)
        AS (CASE WHEN milestone_id IS NULL AND breach_case_id IS NULL THEN contract_id END) STORED,
    ADD CONSTRAINT uk_remedy_decision_contract_level UNIQUE (contract_level_key),
    ADD CONSTRAINT chk_remedy_decision_milestone CHECK (milestone_id IS NOT NULL OR breach_case_id IS NULL);
