package com.agricontract.escrow.application.dto;

import java.math.BigDecimal;

/**
 * bank.refund_completed / _failed or bank.release_completed / _failed, parsed. Answers either an activation deposit
 * return (#247) or a milestone settlement leg (#248, fundType MILESTONE_PAYMENT). sourceEventId is the request eventId
 * escrow sent. ledgerEntryId only when returned, failureReason only when refused; milestoneId may be null.
 */
public record ReturnResultCommand(String eventId, String correlationId, String contractId, String sourceEventId,
                                  String userId, String entryType, String fundType, String milestoneId,
                                  BigDecimal amount, boolean returned, String ledgerEntryId, String failureReason) {

    public static final String MILESTONE_PAYMENT = "MILESTONE_PAYMENT";

    /** The result of a settlement leg: milestone money, not a deposit. */
    public boolean isSettlementLeg() {
        return MILESTONE_PAYMENT.equals(fundType);
    }
}
