package com.agricontract.contract.domain.model.remedy;

import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.util.Objects;

/**
 * One money leg of a RemedyDecision. role is the payee (D86). remedyLegId is the dedup key escrow uses; amount is
 * always positive, a zero leg is never built.
 */
public record RemedyLeg(String remedyLegId, RemedyType remedyType, RemedyFundType fundType, SignerRole role,
                        VndAmount amount) {
    public RemedyLeg {
        Objects.requireNonNull(remedyLegId, "remedyLegId");
        Objects.requireNonNull(remedyType, "remedyType");
        Objects.requireNonNull(fundType, "fundType");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(amount, "amount");
        if (amount.value().signum() <= 0) {
            throw new IllegalArgumentException("a remedy leg amount must be positive");
        }
    }
}
