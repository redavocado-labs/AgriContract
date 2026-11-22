package com.agricontract.escrow.domain.model;

/** A bank pool of locked money: a deposit of the contract, or the batch of one milestone. */
public record FundPool(String fundType, String milestoneId) {

    public static FundPool of(String fundType, String milestoneId) {
        return new FundPool(fundType, SettlementLeg.MILESTONE_PAYMENT.equals(fundType) ? milestoneId : null);
    }
}
