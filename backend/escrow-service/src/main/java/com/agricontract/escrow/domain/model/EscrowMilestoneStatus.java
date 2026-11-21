package com.agricontract.escrow.domain.model;

/**
 * Where one milestone's funding stands with the bank (#120, milestone-escrow design §6b).
 * <ul>
 *   <li>FUNDING_PENDING: bank.lock_requested sent, no result yet; or the last result was bank.lock_failed and the
 *       same request is resent at nextAttemptAt.</li>
 *   <li>FUNDING_FAILED: the last retry failed too (D69); escrow.milestone_funding_failed was written.</li>
 *   <li>LOCKED: bank.lock_completed; the bank ledger holds the batch amount.</li>
 * </ul>
 */
public enum EscrowMilestoneStatus {
    FUNDING_PENDING,
    FUNDING_FAILED,
    LOCKED
}
