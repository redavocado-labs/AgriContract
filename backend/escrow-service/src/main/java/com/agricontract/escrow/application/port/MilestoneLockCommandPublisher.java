package com.agricontract.escrow.application.port;

import com.agricontract.escrow.domain.model.EscrowMilestone;

// Writes bank.lock_requested for a milestone's batch to the outbox (#120). Must run inside the caller's transaction,
// so a command is never sent for a funding that rolled back.
public interface MilestoneLockCommandPublisher {

    /**
     * bank.lock_requested LOCK_MILESTONE / MILESTONE_PAYMENT: the buyer, the batch amount, eventId = sourceEventId =
     * the milestone's requestEventId.
     */
    void milestoneLockRequested(EscrowMilestone milestone, String correlationId, String causationId);

    /** Sends the milestone's original bank.lock_requested again, same eventId and payload (D27). */
    void resendMilestoneLock(EscrowMilestone milestone);
}
