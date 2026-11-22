package com.agricontract.contract.infrastructure.persistence.breach;

import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseComputedResolution;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.domain.model.breach.BreachReasonCode;
import com.agricontract.contract.domain.model.breach.BreachRequester;
import com.agricontract.contract.domain.model.breach.BreachSeverity;
import com.agricontract.contract.domain.model.breach.ContestStatus;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** breach_case (V20 allegation, V21 decision): inserted once, then updated under its row lock; one per milestone. */
@Repository
@RequiredArgsConstructor
public class JdbcBreachCaseRepository implements BreachCaseRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(BreachCase c) {
        jdbc.update("INSERT INTO breach_case (breach_case_id, contract_id, milestone_id, requested_by,"
                        + " alleged_breaching_role, breach_reason_code, severity, status, evidence_file_ids,"
                        + " reported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.breachCaseId(), c.contractId(), c.milestoneId(), c.requestedBy().name(),
                c.allegedBreachingRole() == null ? null : c.allegedBreachingRole().name(),
                c.breachReasonCode().name(), c.severity().name(), c.status().name(), write(c.evidenceFileIds()),
                Timestamp.from(c.reportedAt()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BreachCase c) {
        int updated = jdbc.update("UPDATE breach_case SET status = ?, final_breaching_role = ?, decision_source = ?,"
                        + " decision_evidence_refs = ?, resolved_at = ?, resolved_by_actor_id = ?, contest_status = ?,"
                        + " contest_deadline = ?, computed_resolution = ? WHERE breach_case_id = ?",
                c.status().name(), c.finalBreachingRole() == null ? null : c.finalBreachingRole().name(),
                c.decisionSource() == null ? null : c.decisionSource().name(),
                c.decisionSource() == null ? null : write(c.decisionEvidenceRefs()),
                c.resolvedAt() == null ? null : Timestamp.from(c.resolvedAt()), c.resolvedByActorId(),
                c.contestStatus().name(), c.contestDeadline() == null ? null : Timestamp.from(c.contestDeadline()),
                c.computedResolution() == null ? null : writeComputed(c.computedResolution()), c.breachCaseId());
        if (updated != 1) {
            throw new IllegalStateException("Breach case " + c.breachCaseId() + " does not exist");
        }
    }

    @Override
    public Optional<BreachCase> findByMilestoneId(String milestoneId) {
        return jdbc.query("SELECT * FROM breach_case WHERE milestone_id = ?", row(), milestoneId).stream()
                .findFirst();
    }

    @Override
    public Optional<BreachCase> findById(String breachCaseId) {
        return jdbc.query("SELECT * FROM breach_case WHERE breach_case_id = ?", row(), breachCaseId).stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BreachCase> findByIdForUpdate(String breachCaseId) {
        return jdbc.query("SELECT * FROM breach_case WHERE breach_case_id = ? FOR UPDATE", row(), breachCaseId)
                .stream().findFirst();
    }

    @Override
    public Page findPage(BreachCaseStatus status, boolean awaitingApproval, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (awaitingApproval) {
            where.append(" AND status <> 'RESOLVED' AND computed_resolution IS NOT NULL");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM breach_case" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<BreachCase> content = jdbc.query("SELECT * FROM breach_case" + where
                + " ORDER BY reported_at DESC, breach_case_id LIMIT ? OFFSET ?", row(), pageArgs.toArray());
        return new Page(content, total == null ? 0 : total);
    }

    private RowMapper<BreachCase> row() {
        return (rs, n) -> {
            String allegedRole = rs.getString("alleged_breaching_role");
            String finalRole = rs.getString("final_breaching_role");
            String source = rs.getString("decision_source");
            String refs = rs.getString("decision_evidence_refs");
            Timestamp resolvedAt = rs.getTimestamp("resolved_at");
            Timestamp deadline = rs.getTimestamp("contest_deadline");
            String computed = rs.getString("computed_resolution");
            return new BreachCase(rs.getString("breach_case_id"), rs.getString("contract_id"),
                    rs.getString("milestone_id"), BreachRequester.valueOf(rs.getString("requested_by")),
                    allegedRole == null ? null : SignerRole.valueOf(allegedRole),
                    BreachReasonCode.valueOf(rs.getString("breach_reason_code")),
                    BreachSeverity.valueOf(rs.getString("severity")),
                    BreachCaseStatus.valueOf(rs.getString("status")), readStrings(rs.getString("evidence_file_ids")),
                    rs.getTimestamp("reported_at").toInstant(),
                    finalRole == null ? null : SignerRole.valueOf(finalRole),
                    source == null ? null : DecisionSource.valueOf(source),
                    refs == null ? List.of() : readStrings(refs), resolvedAt == null ? null : resolvedAt.toInstant(),
                    rs.getString("resolved_by_actor_id"), ContestStatus.valueOf(rs.getString("contest_status")),
                    deadline == null ? null : deadline.toInstant(),
                    computed == null ? null : readComputed(computed));
        };
    }

    private String write(List<String> ids) {
        try {
            return json.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> readStrings(String text) {
        try {
            return json.readValue(text, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** decisionSource is the constant INSPECTOR_L1_5 and is written for readers of the column; money as "...00" strings. */
    private String writeComputed(BreachCaseComputedResolution r) {
        ObjectNode node = json.createObjectNode();
        node.put("inspectionReportId", r.inspectionReportId());
        node.put("issueType", r.issueType().name());
        node.put("acceptedQuantityKg", r.acceptedQuantityKg().stripTrailingZeros().toPlainString());
        node.put("qualityDisposition", r.qualityDisposition() == null ? null : r.qualityDisposition().name());
        node.put("finalBreachingRole", r.finalBreachingRole() == null ? null : r.finalBreachingRole().name());
        node.put("decisionSource", DecisionSource.INSPECTOR_L1_5.name());
        node.put("sellerEntitlementAmount", r.sellerEntitlementAmount().wire());
        node.put("buyerRefundAmount", r.buyerRefundAmount().wire());
        node.put("prevailingRole", r.prevailingRole().name());
        return node.toString();
    }

    private BreachCaseComputedResolution readComputed(String text) {
        try {
            JsonNode n = json.readTree(text);
            return new BreachCaseComputedResolution(n.get("inspectionReportId").asText(),
                    FlagIssueType.valueOf(n.get("issueType").asText()), new BigDecimal(n.get("acceptedQuantityKg").asText()),
                    n.path("qualityDisposition").isNull() ? null
                            : QualityDisposition.valueOf(n.get("qualityDisposition").asText()),
                    n.path("finalBreachingRole").isNull() ? null : SignerRole.valueOf(n.get("finalBreachingRole").asText()),
                    new VndAmount(new BigDecimal(n.get("sellerEntitlementAmount").asText())),
                    new VndAmount(new BigDecimal(n.get("buyerRefundAmount").asText())),
                    SignerRole.valueOf(n.get("prevailingRole").asText()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
