package com.agricontract.escrow.domain.model;

/**
 * Where one milestone's funding stands with the bank (#120, milestone-escrow design §6b).
 * <ul>
 *   <li>FUNDING_PENDING: bank.lock_requested sent, no result yet; or the last result was bank.lock_failed and the
 *       same request is resent at nextAttemptAt.</li>
 *   <li>FUNDING_FAILED: the last retry failed too (D69); escrow.milestone_funding_failed was written.</li>
 *   <li>LOCKED: bank.lock_completed; the bank ledger holds the batch amount.</li>
 *   <li>SETTLING: milestone.settled accepted (#248); the release/refund commands are out, not all confirmed.</li>
 *   <li>SETTLED: the bank confirmed every settlement leg; nothing of the batch is left in the lock.</li>
 * </ul>
 */
public enum EscrowMilestoneStatus {
    FUNDING_PENDING,
    FUNDING_FAILED,
    LOCKED,
    SETTLING,
    SETTLED
}
