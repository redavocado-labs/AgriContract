package com.agricontract.contract.domain.model.milestone;

/** How a milestone reached SETTLED. Only the clean paths exist so far (#123); the dispute paths come with their issues. */
public enum SettlementPath {
    /** The buyer confirmed the receipt. */
    CLEAN_CONFIRMED,
    /** The confirm window passed without a buyer action (confirmedBy SYSTEM_TIMEOUT). */
    CLEAN_AUTO_CONFIRMED
}
