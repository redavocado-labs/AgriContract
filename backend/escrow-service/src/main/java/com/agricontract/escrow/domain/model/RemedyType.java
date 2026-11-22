package com.agricontract.escrow.domain.model;

/** remedyLegs[].remedyType of remedy.finalized (golden-flow-events.yaml $defs.remedyType). */
public enum RemedyType {
    NONE,
    PAYMENT_SETTLEMENT,
    PAYMENT_REFUND,
    DEPOSIT_FORFEITURE,
    CONTRACTUAL_PENALTY,
    DAMAGES_COMPENSATION
}
