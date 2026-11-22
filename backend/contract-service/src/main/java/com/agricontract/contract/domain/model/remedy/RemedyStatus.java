package com.agricontract.contract.domain.model.remedy;

/** PENDING_CONTEST waits for the contest window (#241); FINALIZED has published remedy.finalized. */
public enum RemedyStatus {
    PENDING_CONTEST,
    FINALIZED
}
