package com.agricontract.escrow.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * bank.lock_completed or bank.lock_failed (golden-flow-events.yaml), parsed and checked by the consumer.
 * sourceEventId is the requestEventId of the deposit leg or milestone funding. ledgerEntryId is set only when locked,
 * failureReason only when failed. milestoneId is set for a LOCK_MILESTONE result only. occurredAt is the bank's time
 * of the result.
 */
public record LockResultCommand(
        String eventId,
        String correlationId,
        String contractId,
        String sourceEventId,
        String userId,
        String entryType,
        BigDecimal amount,
        boolean locked,
        String ledgerEntryId,
        String failureReason,
        String milestoneId,
        Instant occurredAt) {

    public static final String LOCK_MILESTONE = "LOCK_MILESTONE";

    /** A milestone funding result (#120), not an activation deposit one. */
    public boolean isMilestoneFunding() {
        return LOCK_MILESTONE.equals(entryType);
    }
}
