package com.agricontract.escrow.domain.model;

/**
 * Where one remedy leg stands with the bank (#249).
 * <ul>
 *   <li>SENT: the bank command is out (first time or a resend), no result yet.</li>
 *   <li>FAILED: the bank refused it; the same command is resent at nextAttemptAt, or none is left (alert).</li>
 *   <li>COMPLETED: the bank confirmed the ledger entry; final.</li>
 * </ul>
 */
public enum RemedyLegStatus {
    SENT,
    FAILED,
    COMPLETED
}
