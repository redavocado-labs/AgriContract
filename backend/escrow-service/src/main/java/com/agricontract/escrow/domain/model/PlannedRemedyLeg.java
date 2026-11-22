package com.agricontract.escrow.domain.model;

import java.math.BigDecimal;

/** A remedy leg mapped to its bank command, before it is written. milestoneId is null for deposit legs. */
public record PlannedRemedyLeg(String remedyLegId, RemedyType remedyType, RemedyEntryType entryType, String fundType,
                               String userId, String milestoneId, BigDecimal amount) {

    /** The money pool this leg draws from. */
    public FundPool pool() {
        return FundPool.of(fundType, milestoneId);
    }
}
