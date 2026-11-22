package com.agricontract.escrow.domain.model;

/**
 * The bank ledger operation of a remedy leg with the command that asks for it (golden-flow-events.yaml
 * bankReleaseRequested / bankRefundRequested / bankSeizeRequested). Mirrors chk_remedy_leg_entry_type.
 */
public enum RemedyEntryType {
    RELEASE_TO_SELLER("bank.release_requested"),
    REFUND_TO_BUYER("bank.refund_requested"),
    DEPOSIT_FORFEITURE("bank.seize_requested"),
    CONTRACTUAL_PENALTY("bank.seize_requested"),
    DAMAGES_COMPENSATION("bank.seize_requested");

    private final String command;

    RemedyEntryType(String command) {
        this.command = command;
    }

    /** eventType of the bank command for this entry type. */
    public String command() {
        return command;
    }
}
