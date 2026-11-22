package com.agricontract.contract.domain.model.breach;

/** The frozen breachReasonCode list (golden-flow-events.yaml, golden-flow-api.yaml BreachReasonCode). */
public enum BreachReasonCode {
    NON_DELIVERY,
    SHORTFALL,
    LATE_DELIVERY,
    QUALITY_BELOW_COMMITTED,
    SIDE_SELLING,
    PRODUCTION_SHOCK_NON_FM,
    FUNDING_FAILURE,
    LATE_PAYMENT,
    FAILURE_TO_RECEIVE,
    WRONGFUL_REJECTION,
    FORCE_MAJEURE,
    MUTUAL_EXIT,
    ACTIVATION_FAILURE
}
