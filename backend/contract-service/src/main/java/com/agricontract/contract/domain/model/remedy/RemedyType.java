package com.agricontract.contract.domain.model.remedy;

/** The frozen remedyType list (golden-flow-events.yaml $defs/remedyType). The dispute path builds three of them. */
public enum RemedyType {
    NONE,
    PAYMENT_SETTLEMENT,
    PAYMENT_REFUND,
    DEPOSIT_FORFEITURE,
    CONTRACTUAL_PENALTY,
    DAMAGES_COMPENSATION
}
