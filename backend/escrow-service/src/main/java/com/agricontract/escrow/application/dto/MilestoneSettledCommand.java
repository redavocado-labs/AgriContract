package com.agricontract.escrow.application.dto;

import java.math.BigDecimal;

/**
 * milestone.settled (golden-flow-events.yaml milestoneSettled), parsed and checked by the consumer. sellerId and
 * buyerId come from recipients (role SELLER / BUYER); either may be null when the event does not name that role.
 * The recipients' emails are not carried: escrow moves money only.
 */
public record MilestoneSettledCommand(
        String eventId,
        String correlationId,
        String contractId,
        String milestoneId,
        BigDecimal lockedAmount,
        BigDecimal actualAmount,
        String sellerId,
        String buyerId) {
}
