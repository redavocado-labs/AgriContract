package com.agricontract.contract.infrastructure.persistence.inspection;

import com.agricontract.contract.domain.model.inspection.InspectionReport;
import com.agricontract.contract.domain.model.inspection.InspectionReportPurpose;
import com.agricontract.contract.domain.model.quality.CoffeeActualMetrics;
import com.agricontract.contract.domain.model.quality.MeasurementStatus;
import com.agricontract.contract.domain.model.quality.MetricOutcome;
import com.agricontract.contract.domain.model.quality.QualityAssessment;
import com.agricontract.contract.domain.model.terms.CoffeeType;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.repository.InspectionReportRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** inspection_report and quality_assessment (V22, V24): insert and read only, the rows are immutable. */
@Repository
@RequiredArgsConstructor
public class JdbcInspectionReportRepository implements InspectionReportRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(InspectionReport r, QualityAssessment a, InspectionReportPurpose purpose) {
        jdbc.update("INSERT INTO inspection_report (report_id, contract_id, milestone_id, tier, measurement_status,"
                        + " measured_quantity_kg, accepted_quantity_kg, actual_metrics, result_hash, report_hash,"
                        + " confirmed_at, received_event_id, created_at, `round`, purpose)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.reportId(), r.contractId(), r.milestoneId(), r.tier(), r.measurementStatus().name(),
                r.measuredQuantityKg(), r.acceptedQuantityKg(), r.actualMetrics() == null ? null : write(r.actualMetrics()),
                r.resultHash(), r.reportHash(), Timestamp.from(r.confirmedAt()), r.receivedEventId(),
                Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)),
                countByMilestoneId(r.milestoneId()) + 1, purpose.name());
        jdbc.update("INSERT INTO quality_assessment (report_id, disposition, type_outcome, metric_outcomes,"
                        + " quality_discount_rate, quality_discount_amount, quantity_adjusted_entitlement,"
                        + " final_seller_entitlement, buyer_refund_amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.reportId(), a.disposition().name(), a.typeOutcome() == null ? null : a.typeOutcome().name(),
                write(a.metricOutcomes()), a.qualityDiscountRate(), value(a.qualityDiscountAmount()),
                value(a.quantityAdjustedEntitlement()), value(a.finalSellerEntitlement()),
                value(a.buyerRefundAmount()));
    }

    @Override
    public Optional<InspectionReport> findByMilestoneId(String milestoneId) {
        return jdbc.query("SELECT * FROM inspection_report WHERE milestone_id = ? ORDER BY `round` DESC LIMIT 1",
                (rs, n) -> new InspectionReport(
                rs.getString("report_id"), rs.getString("contract_id"), rs.getString("milestone_id"),
                rs.getString("tier"), MeasurementStatus.valueOf(rs.getString("measurement_status")),
                rs.getBigDecimal("measured_quantity_kg"), rs.getBigDecimal("accepted_quantity_kg"),
                metrics(rs.getString("actual_metrics")), rs.getString("result_hash"), rs.getString("report_hash"),
                rs.getTimestamp("confirmed_at").toInstant(), rs.getString("received_event_id")), milestoneId)
                .stream().findFirst();
    }

    @Override
    public int countByMilestoneId(String milestoneId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE milestone_id = ?",
                Integer.class, milestoneId);
        return n == null ? 0 : n;
    }

    @Override
    public boolean existsByMilestoneIdAndPurpose(String milestoneId, InspectionReportPurpose purpose) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE milestone_id = ? AND purpose = ?",
                Integer.class, milestoneId, purpose.name());
        return n != null && n > 0;
    }

    @Override
    public boolean existsCompleteByMilestoneId(String milestoneId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE milestone_id = ?"
                + " AND measurement_status = 'COMPLETE'", Integer.class, milestoneId);
        return n != null && n > 0;
    }

    private static BigDecimal value(VndAmount amount) {
        return amount == null ? null : amount.value();
    }

    private String write(CoffeeActualMetrics m) {
        ObjectNode node = json.createObjectNode();
        node.put("commodity", "COFFEE");
        node.put("type", m.type().name());
        node.put("moisturePercent", m.moisturePercent().toPlainString());
        node.put("foreignMatterPercent", m.foreignMatterPercent().toPlainString());
        node.put("blackBrokenBeansPercent", m.blackBrokenBeansPercent().toPlainString());
        return node.toString();
    }

    private CoffeeActualMetrics metrics(String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode n = json.readTree(text);
            return new CoffeeActualMetrics(CoffeeType.valueOf(n.get("type").asText()),
                    new BigDecimal(n.get("moisturePercent").asText()),
                    new BigDecimal(n.get("foreignMatterPercent").asText()),
                    new BigDecimal(n.get("blackBrokenBeansPercent").asText()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decimals as strings, so the exact value is kept whatever reads the JSON column back. */
    private String write(java.util.List<MetricOutcome> outcomes) {
        ArrayNode array = json.createArrayNode();
        for (MetricOutcome o : outcomes) {
            ObjectNode node = array.addObject();
            node.put("metric", o.metric());
            node.put("committed", o.committed().toPlainString());
            node.put("actual", o.actual().toPlainString());
            node.put("deviation", o.deviation().toPlainString());
            node.put("zone", o.zone().name());
            node.put("discountRateApplied", o.discountRateApplied().toPlainString());
        }
        return array.toString();
    }
}
