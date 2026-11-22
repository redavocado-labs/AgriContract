package com.agricontract.contract.domain.model.inspection;

/** Why contract-service asks for a Level 1.5 inspection (golden-flow-events.yaml inspection.level1_5_requested reason). */
public enum InspectionRequestReason {
    /** A contested milestone (D80, D82). */
    DISPUTE_ROUTED,
    /** The contract requires a report before settlement and the milestone entered BUYER_RECEIVED (D77). */
    MANDATORY_INSPECTION,
    /** The party held at fault contested the decision (D85, D91, GB-50). */
    DECISION_CONTESTED
}
