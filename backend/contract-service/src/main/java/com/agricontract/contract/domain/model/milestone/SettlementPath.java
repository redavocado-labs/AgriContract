package com.agricontract.contract.domain.model.milestone;

/** How a milestone reached SETTLED. The other dispute paths come with their issues. */
public enum SettlementPath {
    /** The buyer confirmed the receipt. */
    CLEAN_CONFIRMED,
    /** The confirm window passed without a buyer action (confirmedBy SYSTEM_TIMEOUT). */
    CLEAN_AUTO_CONFIRMED,
    /** A QUANTITY flag the seller left unanswered: settled on the buyer's weight (D82). */
    SELLER_SILENT_QUANTITY
}
