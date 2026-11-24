package com.agricontract.contract.domain.model.breach;

/**
 * Who made a decision (golden-flow-events decisionSource). SYSTEM decides without a breach case (the normal completion,
 * #238; Basket A, #240), so it is never the source of a BreachCase resolution (golden-flow-api
 * BreachCaseResolutionRequest has no SYSTEM).
 */
public enum DecisionSource {
    SYSTEM,
    ARBITRATOR,
    INSPECTOR_L1_5,
    INSPECTOR_L2,
    MUTUAL
}
