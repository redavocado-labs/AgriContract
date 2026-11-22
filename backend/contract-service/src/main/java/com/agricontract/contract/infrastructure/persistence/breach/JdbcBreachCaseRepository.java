package com.agricontract.contract.infrastructure.persistence.breach;

import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.domain.model.breach.BreachReasonCode;
import com.agricontract.contract.domain.model.breach.BreachRequester;
import com.agricontract.contract.domain.model.breach.BreachSeverity;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** breach_case (V20): the allegation of a flagged milestone, inserted once; one per milestone. */
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
    public Optional<BreachCase> findByMilestoneId(String milestoneId) {
        return jdbc.query("SELECT * FROM breach_case WHERE milestone_id = ?", (rs, n) -> {
            String role = rs.getString("alleged_breaching_role");
            return new BreachCase(rs.getString("breach_case_id"), rs.getString("contract_id"),
                    rs.getString("milestone_id"), BreachRequester.valueOf(rs.getString("requested_by")),
                    role == null ? null : SignerRole.valueOf(role),
                    BreachReasonCode.valueOf(rs.getString("breach_reason_code")),
                    BreachSeverity.valueOf(rs.getString("severity")),
                    BreachCaseStatus.valueOf(rs.getString("status")), read(rs.getString("evidence_file_ids")),
                    rs.getTimestamp("reported_at").toInstant());
        }, milestoneId).stream().findFirst();
    }

    private String write(List<String> ids) {
        try {
            return json.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> read(String text) {
        try {
            return json.readValue(text, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
