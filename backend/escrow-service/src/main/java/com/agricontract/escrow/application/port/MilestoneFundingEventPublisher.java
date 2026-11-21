package com.agricontract.escrow.application.port;

import com.agricontract.escrow.domain.model.EscrowMilestone;

/**
 * Milestone funding outcomes to the outbox (#120). Must run inside the caller's transaction, so the event exists
 * exactly when the milestone state that caused it commits.
 */
public interface MilestoneFundingEventPublisher {

    /** escrow.milestone_funded (golden-flow-events.yaml escrowMilestoneFunded): the batch is locked at lockedAt. */
    void milestoneFunded(EscrowMilestone milestone, String correlationId, String causationId);

    /**
     * escrow.milestone_funding_failed (escrowMilestoneFundingFailed): the last retry failed too; reason is the bank's
     * last failure reason (D69).
     */
    void milestoneFundingFailed(EscrowMilestone milestone, String correlationId, String causationId);
}
