package com.agricontract.contract.domain.model.quality;

import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.util.List;

/** Money fields and rate are null when the disposition is INCONCLUSIVE; outcomes are then empty. */
public record QualityAssessment(
        QualityDisposition disposition,
        TypeOutcome typeOutcome,
        List<MetricOutcome> metricOutcomes,
        BigDecimal qualityDiscountRate,
        VndAmount qualityDiscountAmount,
        VndAmount quantityAdjustedEntitlement,
        VndAmount finalSellerEntitlement,
        VndAmount buyerRefundAmount) {
    public QualityAssessment {
        metricOutcomes = List.copyOf(metricOutcomes);
    }
}
