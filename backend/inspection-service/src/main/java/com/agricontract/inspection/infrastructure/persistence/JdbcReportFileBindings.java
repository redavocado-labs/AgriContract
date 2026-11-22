package com.agricontract.inspection.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** D62 local binding and recovery state. This class never calls file-service. */
@Repository
public class JdbcReportFileBindings implements com.agricontract.inspection.application.port.ReportFileBindings {
    private final JdbcTemplate jdbc;

    public JdbcReportFileBindings(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(String fileId, String commissionId, Instant uploadedAt) {
        try {
            jdbc.update("INSERT INTO level1_5_report_file(file_id,commission_id,uploaded_at) VALUES (?,?,?)",
                    fileId, commissionId, Timestamp.from(uploadedAt));
        } catch (DuplicateKeyException duplicate) {
            if (!belongsTo(fileId, commissionId)) throw new BindingConflict();
        }
    }

    public boolean belongsTo(String fileId, String commissionId) {
        return !jdbc.queryForList("SELECT file_id FROM level1_5_report_file WHERE file_id=? AND commission_id=?",
                String.class, fileId, commissionId).isEmpty();
    }

    /** Only files referenced by a committed report are eligible for attachment confirmation. */
    public List<String> pendingAttachments(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive limit required");
        return jdbc.queryForList("""
                SELECT f.file_id FROM level1_5_report_file f
                JOIN inspection_report r ON r.report_file_id=f.file_id AND r.l15_commission_id=f.commission_id
                WHERE f.attached_confirmed_at IS NULL ORDER BY f.uploaded_at, f.file_id LIMIT ?
                """, String.class, limit);
    }

    public boolean isPendingAttachment(String fileId) {
        return !jdbc.queryForList("""
                SELECT f.file_id FROM level1_5_report_file f
                JOIN inspection_report r ON r.report_file_id=f.file_id AND r.l15_commission_id=f.commission_id
                WHERE f.file_id=? AND f.attached_confirmed_at IS NULL
                """, String.class, fileId).isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markAttached(String fileId, Instant confirmedAt) {
        return jdbc.update("""
                UPDATE level1_5_report_file f SET attached_confirmed_at=?
                WHERE f.file_id=? AND f.attached_confirmed_at IS NULL
                  AND EXISTS (SELECT 1 FROM inspection_report r
                              WHERE r.report_file_id=f.file_id AND r.l15_commission_id=f.commission_id)
                """, Timestamp.from(confirmedAt), fileId) == 1;
    }
}
