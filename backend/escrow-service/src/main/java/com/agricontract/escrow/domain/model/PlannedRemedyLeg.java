package com.agricontract.escrow.domain.model;

import java.math.BigDecimal;

/**
 * A remedy leg mapped to its bank command, before it is written. milestoneId is null for deposit legs; payeeRole is
 * the role of the remedy.finalized leg (BUYER or SELLER, D86), the party userId belongs to.
 */
public record PlannedRemedyLeg(String remedyLegId, RemedyType remedyType, RemedyEntryType entryType, String fundType,
                               String userId, String payeeRole, String milestoneId, BigDecimal amount) {

    /** The money pool this leg draws from. */
    public FundPool pool() {
        return FundPool.of(fundType, milestoneId);
    }
}
