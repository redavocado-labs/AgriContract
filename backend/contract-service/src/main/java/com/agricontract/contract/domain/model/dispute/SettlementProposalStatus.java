package com.agricontract.contract.domain.model.dispute;

/** OPEN until accepted, withdrawn by the proposer, refused by the other party, or lapsed (D92). */
public enum SettlementProposalStatus {
    OPEN,
    WITHDRAWN,
    REFUSED,
    ACCEPTED,
    LAPSED
}
