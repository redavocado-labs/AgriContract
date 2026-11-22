package com.agricontract.contract.domain.model.remedy;

/**
 * PENDING_CONTEST waits for the contest window (#241); FINALIZED has published remedy.finalized; SUPERSEDED was replaced
 * by the decision of a contest's second Level 1.5 report and never publishes anything.
 */
public enum RemedyStatus {
    PENDING_CONTEST,
    FINALIZED,
    SUPERSEDED
}
