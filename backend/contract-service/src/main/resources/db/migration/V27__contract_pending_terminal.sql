-- #139, D93: a contract reaches SETTLED (TERMINATED in BF-05) only after escrow.contract_lock_cleared names its final
-- remedy decision. Until then it stays ACTIVE and remembers which decision it waits for and the status it will take.
-- The pending columns stay set after the commit, as evidence of the decision that closed the contract. All additive.
ALTER TABLE contracts
    ADD COLUMN pending_terminal_decision_id CHAR(36)     NULL,
    ADD COLUMN pending_terminal_status      VARCHAR(20)  NULL,
    ADD COLUMN settled_at                   TIMESTAMP(6) NULL,
    ADD CONSTRAINT chk_contracts_pending_terminal
        CHECK ((pending_terminal_decision_id IS NULL) = (pending_terminal_status IS NULL));
