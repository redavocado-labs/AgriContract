package com.agricontract.contract.domain.model.quality;

import com.agricontract.contract.domain.model.terms.CoffeeQualityDeviationPolicy;
import com.agricontract.contract.domain.model.terms.CoffeeQualitySpec;
import com.agricontract.contract.domain.model.terms.CoffeeQualityTerms;
import com.agricontract.contract.domain.model.terms.DeviationDirection;
import com.agricontract.contract.domain.model.terms.NumericDeviationPolicy;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic coffee quality disposition (D89). No reviewer, override or extra criterion exists.
 * <pre>
 * deviation (pp, bad direction only): MAXIMUM max(0, actual - committed); MINIMUM max(0, committed - actual)
 * zone: dev &lt;= tolerance WITHIN_TOLERANCE | dev &lt; penalty ABOVE_TOLERANCE | dev &lt; reject PENALTY | else REJECT
 *       (tolerance == penalty: a deviation equal to both is PENALTY)
 * disposition: INCONCLUSIVE status | any REJECT or type MISMATCH NON_CONFORMING | any PENALTY PARTIALLY | CONFORMING
 * quantityAdjusted = min(L, ofDong(Q * P))
 * PARTIALLY: rate = max discountRate of PENALTY metrics (never summed); discount = ofDong(quantityAdjusted * rate)
 * final = quantityAdjusted - discount (NON_CONFORMING: 0)      buyerRefund = L - final
 * </pre>
 * Rounded once; the refund is a subtraction. Penalties and forfeiture are not computed here.
 */
public final class QualityDispositionCalculator {
    private QualityDispositionCalculator() {
    }

    public static QualityAssessment assess(CoffeeQualityTerms terms, MeasurementStatus status,
                                           CoffeeActualMetrics actual, BigDecimal acceptedQuantityKg,
                                           VndAmount effectiveUnitPrice, VndAmount lockedAmount) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(status, "status");
        if (status == MeasurementStatus.INCONCLUSIVE) {
            return new QualityAssessment(QualityDisposition.INCONCLUSIVE, null, List.of(), null, null, null, null,
                    null);
        }
        if (actual == null) {
            throw new InvalidQualityInputException("actual metrics are required when the measurement is complete");
        }
        if (acceptedQuantityKg == null || acceptedQuantityKg.signum() < 0) {
            throw new InvalidQualityInputException("acceptedQuantityKg must be zero or positive");
        }
        if (effectiveUnitPrice == null || lockedAmount == null) {
            throw new InvalidQualityInputException("effectiveUnitPrice and lockedAmount are required");
        }
        CoffeeQualitySpec spec = terms.committedSpec();
        CoffeeQualityDeviationPolicy policy = terms.deviationPolicy();

        TypeOutcome typeOutcome = actual.type() != spec.type() && policy.type() != null
                && Boolean.TRUE.equals(policy.type().rejectOnMismatch()) ? TypeOutcome.MISMATCH : TypeOutcome.MATCH;

        List<MetricOutcome> outcomes = new ArrayList<>(3);
        outcomes.add(outcome("moisture", spec.moisturePercent(), actual.moisturePercent(),
                policy.moisturePercent()));
        outcomes.add(outcome("foreignMatter", spec.foreignMatterPercent(), actual.foreignMatterPercent(),
                policy.foreignMatterPercent()));
        outcomes.add(outcome("blackBrokenBeans", spec.blackBrokenBeansPercent(), actual.blackBrokenBeansPercent(),
                policy.blackBrokenBeansPercent()));

        boolean reject = typeOutcome == TypeOutcome.MISMATCH
                || outcomes.stream().anyMatch(o -> o.zone() == MetricZone.REJECT);
        BigDecimal maxRate = outcomes.stream().filter(o -> o.zone() == MetricZone.PENALTY)
                .map(MetricOutcome::discountRateApplied).max(BigDecimal::compareTo).orElse(null);
        QualityDisposition disposition = reject ? QualityDisposition.NON_CONFORMING
                : maxRate != null ? QualityDisposition.PARTIALLY_CONFORMING : QualityDisposition.CONFORMING;

        VndAmount adjusted = VndAmount.ofDong(acceptedQuantityKg.multiply(effectiveUnitPrice.value()));
        if (adjusted.compareTo(lockedAmount) > 0) {
            adjusted = lockedAmount;
        }
        BigDecimal rate = BigDecimal.ZERO;
        VndAmount discount = VndAmount.ofDong(BigDecimal.ZERO);
        VndAmount fin;
        if (disposition == QualityDisposition.NON_CONFORMING) {
            fin = VndAmount.ofDong(BigDecimal.ZERO);
        } else {
            if (disposition == QualityDisposition.PARTIALLY_CONFORMING) {
                rate = maxRate;
                discount = VndAmount.ofDong(adjusted.value().multiply(rate));
            }
            fin = new VndAmount(adjusted.value().subtract(discount.value()));
        }
        VndAmount refund = new VndAmount(lockedAmount.value().subtract(fin.value()));
        if (fin.value().add(refund.value()).compareTo(lockedAmount.value()) != 0) {
            throw new IllegalStateException("final + refund must equal the locked amount");
        }
        return new QualityAssessment(disposition, typeOutcome, outcomes, rate, discount, adjusted, fin, refund);
    }

    private static MetricOutcome outcome(String name, BigDecimal committed, BigDecimal actual,
                                         NumericDeviationPolicy p) {
        BigDecimal raw = p.direction() == DeviationDirection.MINIMUM ? committed.subtract(actual)
                : actual.subtract(committed);
        BigDecimal dev = raw.signum() < 0 ? BigDecimal.ZERO : raw;
        MetricZone zone;
        if (dev.compareTo(p.reject()) >= 0) {
            zone = MetricZone.REJECT;
        } else if (dev.compareTo(p.penalty()) >= 0) {
            zone = MetricZone.PENALTY;
        } else if (dev.compareTo(p.tolerance()) > 0) {
            zone = MetricZone.ABOVE_TOLERANCE;
        } else {
            zone = MetricZone.WITHIN_TOLERANCE;
        }
        BigDecimal applied = zone == MetricZone.PENALTY ? p.discountRate() : BigDecimal.ZERO;
        return new MetricOutcome(name, committed, actual, dev, zone, applied);
    }
}
