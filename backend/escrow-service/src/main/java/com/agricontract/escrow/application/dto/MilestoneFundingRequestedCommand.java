package com.agricontract.escrow.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** milestone.funding_requested (golden-flow-events.yaml milestoneFundingRequested), parsed and checked by the consumer. */
public record MilestoneFundingRequestedCommand(
        String eventId,
        String correlationId,
        String contractId,
        String milestoneId,
        int milestoneIndex,
        String buyerId,
        BigDecimal batchAmount,
        Instant requestedAt) {
}
