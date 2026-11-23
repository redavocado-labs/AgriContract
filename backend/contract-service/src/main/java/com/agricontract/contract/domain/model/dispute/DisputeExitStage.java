package com.agricontract.contract.domain.model.dispute;

/** The moments at which the parties may end a milestone dispute themselves (D57, D92). */
public enum DisputeExitStage {
    /** The buyer's flag waits for the seller's response; no case yet. Concession only. */
    AWAITING_SELLER_RESPONSE,
    /** The flag was contested; the Level 1.5 report of this round has not decided it yet. */
    CONTESTED_FLAG,
    /** The seller contested a decision with fault (GB-50); the contest's report has not decided it yet. */
    CONTESTED_DECISION
}
