package com.agricontract.contract.domain.model.breach;

/** REPORTED, then UNDER_REVIEW by an Arbitrator, then RESOLVED by an AttributionDecision (D80, D81). */
public enum BreachCaseStatus {
    REPORTED,
    UNDER_REVIEW,
    RESOLVED
}
