package com.agricontract.contract.domain.model.quality;

import java.math.BigDecimal;

/** discountRateApplied is the metric's own rate when zone is PENALTY, otherwise zero. */
public record MetricOutcome(
        String metric,
        BigDecimal committed,
        BigDecimal actual,
        BigDecimal deviation,
        MetricZone zone,
        BigDecimal discountRateApplied) {
}
