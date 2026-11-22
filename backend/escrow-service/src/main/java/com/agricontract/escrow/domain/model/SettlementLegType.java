package com.agricontract.escrow.domain.model;

/**
 * Which way the locked money of a settlement leg goes, with the bank command that moves it
 * (golden-flow-events.yaml bankReleaseRequested / bankRefundRequested). Mirrors chk_settlement_leg_entry_type.
 */
public enum SettlementLegType {
    /** The seller is paid from the lock. */
    RELEASE_TO_SELLER("bank.release_requested"),
    /** The buyer gets back what the seller is not paid. */
    REFUND_TO_BUYER("bank.refund_requested");

    private final String command;

    SettlementLegType(String command) {
        this.command = command;
    }

    /** eventType of the bank command for this leg. */
    public String command() {
        return command;
    }
}
