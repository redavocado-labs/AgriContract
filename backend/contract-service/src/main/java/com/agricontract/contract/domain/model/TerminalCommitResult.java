package com.agricontract.contract.domain.model;

/** Outcome of {@link Contract#commitTerminal} on an escrow.contract_lock_cleared (D93). */
public enum TerminalCommitResult {
    /** The pending decision was cleared: the contract took its terminal status. */
    COMMITTED,
    /** The contract is not ACTIVE (already terminal, or never activated): nothing to commit. */
    NOT_ACTIVE,
    /** No final decision is waiting: the lock cleared after an earlier, non-final remedy. */
    NOTHING_PENDING,
    /** The pending decision is not among the cleared ones: its legs have not all completed yet. */
    PENDING_NOT_CLEARED
}
