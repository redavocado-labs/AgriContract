package com.agricontract.contract.domain.model.breach;

import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * D81: the deterministic result computed from the confirmed Level 1.5 report of a contested milestone (BreachCase
 * computedResolution). decisionSource is always INSPECTOR_L1_5, so it is not a field. finalBreachingRole is SELLER only
 * for NON_CONFORMING, else null; qualityDisposition is null for QUANTITY. The Arbitrator only approves it.
 */
public record BreachCaseComputedResolution(String inspectionReportId, FlagIssueType issueType,
                                           BigDecimal acceptedQuantityKg, QualityDisposition qualityDisposition,
                                           SignerRole finalBreachingRole, VndAmount sellerEntitlementAmount,
                                           VndAmount buyerRefundAmount, SignerRole prevailingRole) {
    public BreachCaseComputedResolution {
        Objects.requireNonNull(inspectionReportId, "inspectionReportId");
        Objects.requireNonNull(issueType, "issueType");
        Objects.requireNonNull(acceptedQuantityKg, "acceptedQuantityKg");
        Objects.requireNonNull(sellerEntitlementAmount, "sellerEntitlementAmount");
        Objects.requireNonNull(buyerRefundAmount, "buyerRefundAmount");
        Objects.requireNonNull(prevailingRole, "prevailingRole");
        if (acceptedQuantityKg.signum() < 0) {
            throw new IllegalArgumentException("acceptedQuantityKg must not be negative");
        }
        if (finalBreachingRole == SignerRole.BUYER) {
            throw new IllegalArgumentException("a computed resolution never blames the buyer");
        }
    }
}
