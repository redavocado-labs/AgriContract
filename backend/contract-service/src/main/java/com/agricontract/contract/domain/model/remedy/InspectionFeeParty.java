package com.agricontract.contract.domain.model.remedy;

import com.agricontract.contract.domain.model.consent.SignerRole;

/**
 * Who bears the Level 1.5 inspection fee of a RemedyDecision (D57, D92): one party, or SPLIT between both. A decision
 * with no fee has none (null). Evidence only: no ledger leg, remedy.finalized does not carry it.
 */
public enum InspectionFeeParty {
    BUYER,
    SELLER,
    SPLIT;

    public static InspectionFeeParty of(SignerRole role) {
        return role == SignerRole.BUYER ? BUYER : SELLER;
    }
}
