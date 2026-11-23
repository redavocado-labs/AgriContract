package com.agricontract.contract.domain.model.dispute;

import com.agricontract.contract.domain.model.consent.ConsentActionType;

/** How the parties end a milestone dispute themselves (D57): a concession or an agreed settlement. */
public enum DisputeExitType {
    CONCEDE(ConsentActionType.CONCEDE_DISPUTE),
    SETTLE(ConsentActionType.SETTLE_DISPUTE);

    private final ConsentActionType action;

    DisputeExitType(ConsentActionType action) {
        this.action = action;
    }

    /** The consented action (and OTP action) of this exit. */
    public ConsentActionType action() {
        return action;
    }
}
