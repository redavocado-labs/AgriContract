package com.agricontract.inspection.infrastructure.persistence;

import com.agricontract.inspection.application.port.ReportSubmissionIdempotency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Repository
public class JdbcReportSubmissionIdempotency implements ReportSubmissionIdempotency {
    private final JdbcTemplate jdbc;
    public JdbcReportSubmissionIdempotency(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Receipt> find(String inspectorId, String key) {
        return jdbc.query("SELECT request_hash,report_id FROM level1_5_report_submission_receipt WHERE inspector_id=? AND idempotency_key=?",
                (rs, row) -> new Receipt(rs.getString("request_hash"), rs.getString("report_id")),
                inspectorId, key.getBytes(StandardCharsets.US_ASCII)).stream().findFirst();
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void insert(String inspectorId, String key, String requestHash, String reportId) {
        try {
            jdbc.update("INSERT INTO level1_5_report_submission_receipt(inspector_id,idempotency_key,request_hash,report_id) VALUES (?,?,?,?)",
                    inspectorId, key.getBytes(StandardCharsets.US_ASCII), requestHash, reportId);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) { throw new ConcurrentKey(duplicate); }
    }
}
