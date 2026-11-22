package com.agricontract.escrow.application.port;

import com.agricontract.escrow.domain.model.RemedyLeg;

// Writes the bank command of a remedy leg to the outbox (#249). Must run inside the caller's transaction.
public interface RemedyCommandPublisher {

    /**
     * bank.release_requested, bank.refund_requested or bank.seize_requested for the leg, with remedyDecisionId and
     * remedyLegId set; eventId = sourceEventId = the leg's requestEventId.
     */
    void requested(RemedyLeg leg, String correlationId, String causationId);

    /** Sends the leg's original command again, same eventId and payload (D27). */
    void resend(RemedyLeg leg);
}
