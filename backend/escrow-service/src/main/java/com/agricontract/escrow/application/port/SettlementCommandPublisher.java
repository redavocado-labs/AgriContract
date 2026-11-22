package com.agricontract.escrow.application.port;

import com.agricontract.escrow.domain.model.SettlementLeg;

// Writes the bank command of a settlement leg to the outbox (#248). Must run inside the caller's transaction, so a
// command is never sent for a settlement that rolled back.
public interface SettlementCommandPublisher {

    /**
     * bank.release_requested RELEASE_TO_SELLER or bank.refund_requested REFUND_TO_BUYER for the leg: its user, fund,
     * milestone and amount, eventId = sourceEventId = the leg's requestEventId, no remedy (clean path).
     */
    void settlementLegRequested(SettlementLeg leg, String correlationId, String causationId);

    /** Sends the leg's original command again, same eventId and payload (D27). */
    void resend(SettlementLeg leg);
}
