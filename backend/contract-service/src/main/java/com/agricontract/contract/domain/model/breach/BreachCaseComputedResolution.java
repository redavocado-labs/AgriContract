package com.agricontract.contract.domain.model.breach;

import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * D81: the deterministic result computed from the confirmed Level 1.5 report of a contested milestone (BreachCase
 * computedResolution); the Arbitrator only approves it, with decisionSource INSPECTOR_L1_5. finalBreachingRole is
 * SELLER only for NON_CONFORMING, else null; qualityDisposition is null for QUANTITY.
 *
 * <p>D92: a dispute ended by a concession or an agreed settlement carries the position the parties accepted in the
 * same shape ({@link #agreed}), so the RemedyCalculator stays the one money path. Such a resolution has no report
 * (inspectionReportId null) and is decided MUTUAL; prevailingRole is the side whose position was accepted, null for a
 * settlement.
 */
public record BreachCaseComputedResolution(String inspectionReportId, FlagIssueType issueType,
                                           BigDecimal acceptedQuantityKg, QualityDisposition qualityDisposition,
                                           SignerRole finalBreachingRole, VndAmount sellerEntitlementAmount,
                                           VndAmount buyerRefundAmount, SignerRole prevailingRole) {
    public BreachCaseComputedResolution {
        Objects.requireNonNull(issueType, "issueType");
        Objects.requireNonNull(acceptedQuantityKg, "acceptedQuantityKg");
        Objects.requireNonNull(sellerEntitlementAmount, "sellerEntitlementAmount");
        Objects.requireNonNull(buyerRefundAmount, "buyerRefundAmount");
        if (inspectionReportId != null) {
            Objects.requireNonNull(prevailingRole, "prevailingRole");
        }
        if (acceptedQuantityKg.signum() < 0) {
            throw new IllegalArgumentException("acceptedQuantityKg must not be negative");
        }
        if (finalBreachingRole == SignerRole.BUYER) {
            throw new IllegalArgumentException("a computed resolution never blames the buyer");
        }
    }

    /** D92: the position the parties accepted, with no report behind it (decisionSource MUTUAL). */
    public static BreachCaseComputedResolution agreed(FlagIssueType issueType, BigDecimal acceptedQuantityKg,
                                                      QualityDisposition qualityDisposition,
                                                      SignerRole finalBreachingRole, VndAmount sellerEntitlementAmount,
                                                      VndAmount buyerRefundAmount, SignerRole prevailingRole) {
        return new BreachCaseComputedResolution(null, issueType, acceptedQuantityKg, qualityDisposition,
                finalBreachingRole, sellerEntitlementAmount, buyerRefundAmount, prevailingRole);
    }

    /** True for a position the parties accepted (D92), false for the result of a Level 1.5 report (D81). */
    public boolean isAgreed() {
        return inspectionReportId == null;
    }
}
