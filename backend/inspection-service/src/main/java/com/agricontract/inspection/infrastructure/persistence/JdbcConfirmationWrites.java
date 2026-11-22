package com.agricontract.inspection.infrastructure.persistence;

import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.outbox.OutboxWriter;
import com.agricontract.inspection.application.usecase.ConfirmLevel15Report;
import com.agricontract.inspection.domain.model.InspectionSettlementResultV1;
import com.agricontract.inspection.domain.model.ReportEvidence;
import com.agricontract.inspection.domain.model.ReportIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/** Writes only to the inspection database and participates in the confirmation transaction. */
@Component
public class JdbcConfirmationWrites implements ConfirmLevel15Report.ConfirmationWrites {
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;

    public JdbcConfirmationWrites(JdbcTemplate jdbc, OutboxWriter outbox, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertSignature(ReportIdentity identity, Instant authTime, Instant signedAt, String reportHash) {
        jdbc.update("""
                INSERT INTO inspector_signature
                  (signature_id,report_id,contract_id,signer_user_id,auth_time,signed_at,report_hash)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), identity.reportId(), identity.assignment().contractId(),
                identity.assignment().inspectorId(), Timestamp.from(authTime), Timestamp.from(signedAt), reportHash);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendConfirmed(ReportIdentity identity, InspectionSettlementResultV1 result, ReportEvidence evidence) {
        try {
            String confirmedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC).format(evidence.confirmedAt());
            var payload = Map.<String, Object>of(
                    "reportId", identity.reportId(), "contractId", identity.assignment().contractId(),
                    "milestoneId", identity.assignment().milestoneId(), "tier", "LEVEL_1_5",
                    "inspectorId", identity.assignment().inspectorId(),
                    "normalizedResult", mapper.readTree(evidence.normalizedResult()),
                    "resultHash", evidence.resultHash(), "reportFileHash", evidence.reportFileHash(),
                    "reportHash", evidence.reportHash(), "confirmedAt", confirmedAt);
            UUID eventId = UUID.randomUUID();
            outbox.append(new EventEnvelope(eventId, "inspection.report_confirmed", 1,
                    evidence.confirmedAt(), "inspection-service", identity.reportId(), eventId.toString(), null, null, payload));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid normalized result", invalid);
        }
    }
}
