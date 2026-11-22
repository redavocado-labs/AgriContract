package com.agricontract.contract.infrastructure.persistence.milestone;

import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * milestone_evidence_file and file_scan_outcome (V17). The scan status is read by joining the outcome on fileId, so
 * it does not matter whether file.ready arrived before or after the upload was recorded.
 */
@Repository
@RequiredArgsConstructor
public class JdbcMilestoneEvidenceRepository implements MilestoneEvidenceRepository {
    private static final String SELECT = "SELECT e.file_id, e.milestone_id, e.contract_id, e.uploaded_by,"
            + " e.uploader_role, e.uploaded_at, o.status AS scan_status FROM milestone_evidence_file e"
            + " LEFT JOIN file_scan_outcome o ON o.file_id = e.file_id";

    private static final RowMapper<MilestoneEvidenceFile> ROW = (rs, n) -> new MilestoneEvidenceFile(
            rs.getString("file_id"),
            rs.getString("milestone_id"),
            rs.getString("contract_id"),
            rs.getString("uploaded_by"),
            SignerRole.valueOf(rs.getString("uploader_role")),
            rs.getTimestamp("uploaded_at").toInstant(),
            rs.getString("scan_status") == null ? EvidenceScanStatus.PROCESSING
                    : EvidenceScanStatus.valueOf(rs.getString("scan_status")));

    private final JdbcTemplate jdbc;

    @Override
    public Optional<MilestoneEvidenceFile> findByUploaderAndKey(String uploadedBy, String idempotencyKey) {
        return jdbc.query(SELECT + " WHERE e.uploaded_by = ? AND e.idempotency_key = ?", ROW, uploadedBy,
                idempotencyKey).stream().findFirst();
    }

    @Override
    public Optional<MilestoneEvidenceFile> findById(String fileId) {
        return jdbc.query(SELECT + " WHERE e.file_id = ?", ROW, fileId).stream().findFirst();
    }

    @Override
    public Map<String, MilestoneEvidenceFile> findByIds(Collection<String> fileIds) {
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        return new NamedParameterJdbcTemplate(jdbc)
                .query(SELECT + " WHERE e.file_id IN (:ids)", new MapSqlParameterSource("ids", fileIds), ROW)
                .stream()
                .collect(Collectors.toMap(MilestoneEvidenceFile::fileId, Function.identity()));
    }

    @Override
    public List<MilestoneEvidenceFile> findByMilestoneId(String milestoneId) {
        return jdbc.query(SELECT + " WHERE e.milestone_id = ? ORDER BY e.uploaded_at, e.file_id", ROW, milestoneId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(MilestoneEvidenceFile file, String idempotencyKey) {
        jdbc.update("INSERT INTO milestone_evidence_file (file_id, milestone_id, contract_id, uploaded_by,"
                        + " uploader_role, idempotency_key, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                file.fileId(), file.milestoneId(), file.contractId(), file.uploadedBy(), file.uploaderRole().name(),
                idempotencyKey, Timestamp.from(file.uploadedAt()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markNamed(Collection<String> fileIds, Instant namedAt) {
        if (fileIds.isEmpty()) {
            return;
        }
        new NamedParameterJdbcTemplate(jdbc).update("UPDATE milestone_evidence_file SET named_at = :at,"
                        + " next_attach_attempt_at = :at WHERE file_id IN (:ids) AND named_at IS NULL",
                new MapSqlParameterSource("ids", fileIds).addValue("at", Timestamp.from(namedAt)));
    }

    @Override
    public List<PendingAttach> findAttachDue(Instant now, int limit) {
        return jdbc.query("SELECT file_id, attach_attempts FROM milestone_evidence_file"
                        + " WHERE attached_confirmed_at IS NULL AND named_at IS NOT NULL"
                        + " AND next_attach_attempt_at <= ? ORDER BY next_attach_attempt_at, file_id LIMIT ?",
                (rs, n) -> new PendingAttach(rs.getString("file_id"), rs.getInt("attach_attempts")),
                Timestamp.from(now), limit);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAttachFailure(String fileId, Instant nextAttemptAt) {
        jdbc.update("UPDATE milestone_evidence_file SET attach_attempts = attach_attempts + 1,"
                + " next_attach_attempt_at = ? WHERE file_id = ? AND attached_confirmed_at IS NULL",
                Timestamp.from(nextAttemptAt), fileId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAttached(String fileId, Instant confirmedAt) {
        jdbc.update("UPDATE milestone_evidence_file SET attached_confirmed_at = ? WHERE file_id = ?"
                + " AND attached_confirmed_at IS NULL", Timestamp.from(confirmedAt), fileId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordScanOutcome(String fileId, EvidenceScanStatus status, String failureReason,
                                     Instant receivedAt) {
        if (status == EvidenceScanStatus.PROCESSING) {
            throw new IllegalArgumentException("A scan outcome is READY or FAILED");
        }
        String reason = failureReason == null || failureReason.length() <= 500 ? failureReason
                : failureReason.substring(0, 500);
        return jdbc.update("INSERT IGNORE INTO file_scan_outcome (file_id, status, failure_reason, received_at)"
                + " VALUES (?, ?, ?, ?)", fileId, status.name(), reason, Timestamp.from(receivedAt)) == 1;
    }
}
