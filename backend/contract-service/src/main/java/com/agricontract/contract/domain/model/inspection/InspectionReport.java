package com.agricontract.contract.domain.model.inspection;

import com.agricontract.contract.domain.model.quality.CoffeeActualMetrics;
import com.agricontract.contract.domain.model.quality.MeasurementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The immutable result of a confirmed inspection report (inspection.report_confirmed), kept as received once its
 * hashes were verified. actualMetrics is null only when the report is INCONCLUSIVE and its metrics are not usable.
 * receivedEventId is the eventId of the event that delivered it. Only LEVEL_1_5 reports are stored so far.
 */
public record InspectionReport(String reportId, String contractId, String milestoneId, String tier,
                               MeasurementStatus measurementStatus, BigDecimal measuredQuantityKg,
                               BigDecimal acceptedQuantityKg, CoffeeActualMetrics actualMetrics, String resultHash,
                               String reportHash, Instant confirmedAt, String receivedEventId) {
    public static final String LEVEL_1_5 = "LEVEL_1_5";

    public InspectionReport {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(milestoneId, "milestoneId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(measurementStatus, "measurementStatus");
        Objects.requireNonNull(measuredQuantityKg, "measuredQuantityKg");
        Objects.requireNonNull(acceptedQuantityKg, "acceptedQuantityKg");
        Objects.requireNonNull(resultHash, "resultHash");
        Objects.requireNonNull(reportHash, "reportHash");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(receivedEventId, "receivedEventId");
    }
}
