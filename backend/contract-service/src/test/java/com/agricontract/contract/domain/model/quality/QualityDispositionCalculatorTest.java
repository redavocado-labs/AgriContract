package com.agricontract.contract.domain.model.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.model.terms.CoffeeQualityDeviationPolicy;
import com.agricontract.contract.domain.model.terms.CoffeeQualitySpec;
import com.agricontract.contract.domain.model.terms.CoffeeQualityTerms;
import com.agricontract.contract.domain.model.terms.CoffeeType;
import com.agricontract.contract.domain.model.terms.DeviationDirection;
import com.agricontract.contract.domain.model.terms.ExactMatchRejectPolicy;
import com.agricontract.contract.domain.model.terms.NumericDeviationPolicy;
import com.agricontract.contract.domain.model.terms.VndAmount;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** D89 deterministic quality disposition. */
class QualityDispositionCalculatorTest {
    private static final VndAmount L = VndAmount.parse("150000000.00");

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static NumericDeviationPolicy max(String tol, String pen, String rej, String rate) {
        return new NumericDeviationPolicy(DeviationDirection.MAXIMUM, d(tol), d(pen), d(rej), d(rate));
    }

    private static CoffeeQualityTerms terms(NumericDeviationPolicy moisture) {
        return new CoffeeQualityTerms("COFFEE",
                new CoffeeQualitySpec("COFFEE", CoffeeType.ROBUSTA, d("12.5"), d("0.5"), d("5.0")),
                new CoffeeQualityDeviationPolicy(new ExactMatchRejectPolicy(true), moisture,
                        max("0.2", "0.5", "1.0", "0.03"), max("1.0", "2.0", "4.0", "0.01")));
    }

    private static CoffeeQualityTerms terms() {
        return terms(max("0.5", "1.0", "2.0", "0.02"));
    }

    private static CoffeeActualMetrics actual(CoffeeType type, String m, String f, String b) {
        return new CoffeeActualMetrics(type, d(m), d(f), d(b));
    }

    private static QualityAssessment run(CoffeeQualityTerms t, CoffeeActualMetrics a, String q, String p) {
        return QualityDispositionCalculator.assess(t, MeasurementStatus.COMPLETE, a, d(q), VndAmount.parse(p), L);
    }

    private static MetricZone moistureZone(CoffeeQualityTerms t, String moisture) {
        return run(t, actual(CoffeeType.ROBUSTA, moisture, "0.5", "5.0"), "1000", "1000.00").metricOutcomes().get(0)
                .zone();
    }

    @Test
    void partiallyConforming_usesTheMaxRateOnce() {
        QualityAssessment r = run(terms(), actual(CoffeeType.ROBUSTA, "13.5", "0.8", "7.5"), "9800.000", "15000.00");

        assertThat(r.disposition()).isEqualTo(QualityDisposition.PARTIALLY_CONFORMING);
        assertThat(r.metricOutcomes()).extracting(MetricOutcome::metric)
                .containsExactly("moisture", "foreignMatter", "blackBrokenBeans");
        assertThat(r.metricOutcomes()).extracting(MetricOutcome::zone).containsExactly(
                MetricZone.PENALTY, MetricZone.ABOVE_TOLERANCE, MetricZone.PENALTY);
        assertThat(r.metricOutcomes().get(0).deviation()).isEqualByComparingTo("1.0");
        assertThat(r.metricOutcomes().get(1).deviation()).isEqualByComparingTo("0.3");
        assertThat(r.metricOutcomes().get(2).deviation()).isEqualByComparingTo("2.5");
        assertThat(r.metricOutcomes().get(1).discountRateApplied()).isEqualByComparingTo("0");
        assertThat(r.qualityDiscountRate()).isEqualByComparingTo("0.02");
        assertThat(r.quantityAdjustedEntitlement().wire()).isEqualTo("147000000.00");
        assertThat(r.qualityDiscountAmount().wire()).isEqualTo("2940000.00");
        assertThat(r.finalSellerEntitlement().wire()).isEqualTo("144060000.00");
        assertThat(r.buyerRefundAmount().wire()).isEqualTo("5940000.00");
    }

    @Test
    void conforming_andQuantityCappedByLockedAmount() {
        QualityAssessment r = run(terms(), actual(CoffeeType.ROBUSTA, "12.9", "0.5", "4.0"), "10500.000", "15000.00");

        assertThat(r.disposition()).isEqualTo(QualityDisposition.CONFORMING);
        assertThat(r.typeOutcome()).isEqualTo(TypeOutcome.MATCH);
        assertThat(r.metricOutcomes()).extracting(MetricOutcome::zone).containsOnly(MetricZone.WITHIN_TOLERANCE);
        assertThat(r.metricOutcomes().get(0).deviation()).isEqualByComparingTo("0.4");
        assertThat(r.metricOutcomes().get(1).deviation()).isEqualByComparingTo("0");
        assertThat(r.metricOutcomes().get(2).deviation()).isEqualByComparingTo("0");
        assertThat(r.quantityAdjustedEntitlement().wire()).isEqualTo("150000000.00");
        assertThat(r.qualityDiscountAmount().wire()).isEqualTo("0.00");
        assertThat(r.finalSellerEntitlement().wire()).isEqualTo("150000000.00");
        assertThat(r.buyerRefundAmount().wire()).isEqualTo("0.00");
    }

    @Test
    void rounding_isDoneOncePerStep() {
        QualityAssessment r = run(terms(max("0.5", "1.0", "2.0", "0.015")),
                actual(CoffeeType.ROBUSTA, "13.6", "0.5", "5.0"), "9000.123", "15333.00");

        assertThat(r.disposition()).isEqualTo(QualityDisposition.PARTIALLY_CONFORMING);
        assertThat(r.metricOutcomes().get(0).deviation()).isEqualByComparingTo("1.1");
        assertThat(r.qualityDiscountRate()).isEqualByComparingTo("0.015");
        assertThat(r.quantityAdjustedEntitlement().wire()).isEqualTo("137998886.00");
        assertThat(r.qualityDiscountAmount().wire()).isEqualTo("2069983.00");
        assertThat(r.finalSellerEntitlement().wire()).isEqualTo("135928903.00");
        assertThat(r.buyerRefundAmount().wire()).isEqualTo("14071097.00");
    }

    @Test
    void rejectBoundary_refundsEverything() {
        QualityAssessment r = run(terms(), actual(CoffeeType.ROBUSTA, "14.5", "0.5", "5.0"), "9800.000", "15000.00");

        assertThat(r.metricOutcomes().get(0).zone()).isEqualTo(MetricZone.REJECT);
        assertThat(r.disposition()).isEqualTo(QualityDisposition.NON_CONFORMING);
        assertThat(r.qualityDiscountRate()).isEqualByComparingTo("0");
        assertThat(r.qualityDiscountAmount().wire()).isEqualTo("0.00");
        assertThat(r.finalSellerEntitlement().wire()).isEqualTo("0.00");
        assertThat(r.buyerRefundAmount().wire()).isEqualTo("150000000.00");
    }

    @Test
    void typeMismatch_isNonConforming() {
        QualityAssessment r = run(terms(), actual(CoffeeType.ARABICA, "12.5", "0.5", "5.0"), "9800.000", "15000.00");

        assertThat(r.typeOutcome()).isEqualTo(TypeOutcome.MISMATCH);
        assertThat(r.metricOutcomes()).extracting(MetricOutcome::zone).containsOnly(MetricZone.WITHIN_TOLERANCE);
        assertThat(r.disposition()).isEqualTo(QualityDisposition.NON_CONFORMING);
        assertThat(r.finalSellerEntitlement().wire()).isEqualTo("0.00");
        assertThat(r.buyerRefundAmount().wire()).isEqualTo("150000000.00");
    }

    @Test
    void inconclusive_hasNoMoneyEvenWithConformingMetrics() {
        QualityAssessment r = QualityDispositionCalculator.assess(terms(), MeasurementStatus.INCONCLUSIVE,
                actual(CoffeeType.ROBUSTA, "12.5", "0.5", "5.0"), d("9800"), VndAmount.parse("15000.00"), L);

        assertThat(r.disposition()).isEqualTo(QualityDisposition.INCONCLUSIVE);
        assertThat(r.metricOutcomes()).isEmpty();
        assertThat(r.qualityDiscountRate()).isNull();
        assertThat(r.qualityDiscountAmount()).isNull();
        assertThat(r.quantityAdjustedEntitlement()).isNull();
        assertThat(r.finalSellerEntitlement()).isNull();
        assertThat(r.buyerRefundAmount()).isNull();
    }

    @Test
    void inconclusive_allowsMissingMetrics() {
        QualityAssessment r = QualityDispositionCalculator.assess(terms(), MeasurementStatus.INCONCLUSIVE, null,
                d("9800"), VndAmount.parse("15000.00"), L);

        assertThat(r.disposition()).isEqualTo(QualityDisposition.INCONCLUSIVE);
    }

    @Test
    void zoneBoundaries() {
        assertThat(moistureZone(terms(), "13.0")).isEqualTo(MetricZone.WITHIN_TOLERANCE);
        assertThat(moistureZone(terms(), "13.01")).isEqualTo(MetricZone.ABOVE_TOLERANCE);
        assertThat(moistureZone(terms(), "13.49")).isEqualTo(MetricZone.ABOVE_TOLERANCE);
        assertThat(moistureZone(terms(), "13.5")).isEqualTo(MetricZone.PENALTY);
        assertThat(moistureZone(terms(), "14.49")).isEqualTo(MetricZone.PENALTY);
        assertThat(moistureZone(terms(), "14.5")).isEqualTo(MetricZone.REJECT);
        assertThat(moistureZone(terms(max("1.0", "1.0", "2.0", "0.02")), "13.5")).isEqualTo(MetricZone.PENALTY);
        assertThat(moistureZone(terms(max("1.0", "1.0", "2.0", "0.02")), "13.49")).isEqualTo(MetricZone.WITHIN_TOLERANCE);
    }

    @Test
    void minimumDirection_countsOnlyShortfall() {
        CoffeeQualityTerms t = terms(new NumericDeviationPolicy(DeviationDirection.MINIMUM, d("0.5"), d("1.0"),
                d("2.0"), d("0.02")));

        QualityAssessment low = run(t, actual(CoffeeType.ROBUSTA, "11.4", "0.5", "5.0"), "1000", "1000.00");
        QualityAssessment high = run(t, actual(CoffeeType.ROBUSTA, "13.0", "0.5", "5.0"), "1000", "1000.00");

        assertThat(low.metricOutcomes().get(0).deviation()).isEqualByComparingTo("1.1");
        assertThat(low.metricOutcomes().get(0).zone()).isEqualTo(MetricZone.PENALTY);
        assertThat(high.metricOutcomes().get(0).deviation()).isEqualByComparingTo("0");
        assertThat(high.metricOutcomes().get(0).zone()).isEqualTo(MetricZone.WITHIN_TOLERANCE);
    }

    @Test
    void sameInputs_giveEqualAssessments() {
        CoffeeActualMetrics a = actual(CoffeeType.ROBUSTA, "13.5", "0.8", "7.5");

        assertThat(run(terms(), a, "9800.000", "15000.00")).isEqualTo(run(terms(), a, "9800.000", "15000.00"));
    }

    @Test
    void invalidActualMetrics_areRejected() {
        assertThatThrownBy(() -> actual(CoffeeType.ROBUSTA, "12.555", "0.5", "5.0"))
                .isInstanceOf(InvalidQualityInputException.class);
        assertThatThrownBy(() -> actual(CoffeeType.ROBUSTA, "100.01", "0.5", "5.0"))
                .isInstanceOf(InvalidQualityInputException.class);
        assertThatThrownBy(() -> actual(CoffeeType.ROBUSTA, "12.5", "-0.1", "5.0"))
                .isInstanceOf(InvalidQualityInputException.class);
        assertThatThrownBy(() -> new CoffeeActualMetrics(null, d("1"), d("1"), d("1")))
                .isInstanceOf(InvalidQualityInputException.class);
    }

    @Test
    void invalidMoneyInputs_areRejected() {
        CoffeeActualMetrics a = actual(CoffeeType.ROBUSTA, "12.5", "0.5", "5.0");

        assertThatThrownBy(() -> QualityDispositionCalculator.assess(terms(), MeasurementStatus.COMPLETE, a, d("-1"),
                VndAmount.parse("1.00"), L)).isInstanceOf(InvalidQualityInputException.class);
        assertThatThrownBy(() -> QualityDispositionCalculator.assess(terms(), MeasurementStatus.COMPLETE, a, d("1"),
                null, L)).isInstanceOf(InvalidQualityInputException.class);
        assertThatThrownBy(() -> QualityDispositionCalculator.assess(terms(), MeasurementStatus.COMPLETE, a, d("1"),
                VndAmount.parse("1.00"), null)).isInstanceOf(InvalidQualityInputException.class);
    }
}
