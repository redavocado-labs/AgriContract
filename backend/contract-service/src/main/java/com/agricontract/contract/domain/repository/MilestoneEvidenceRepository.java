package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Files uploaded for milestones (D71) and the scan outcomes file-service reported. The scan status of a file is
 * whatever file.ready/file.failed said about its fileId, PROCESSING while nothing arrived; an outcome is kept even if
 * it arrives before the upload is recorded, so a fast scan is never lost.
 */
public interface MilestoneEvidenceRepository {

    /** The file this uploader recorded with this Idempotency-Key, if any (an upload repeated with the same key). */
    Optional<MilestoneEvidenceFile> findByUploaderAndKey(String uploadedBy, String idempotencyKey);

    Optional<MilestoneEvidenceFile> findById(String fileId);

    /** The recorded files among {@code fileIds}, by fileId; unknown ids are absent. */
    Map<String, MilestoneEvidenceFile> findByIds(Collection<String> fileIds);

    /** Every file recorded for this milestone, oldest first. */
    List<MilestoneEvidenceFile> findByMilestoneId(String milestoneId);

    void insert(MilestoneEvidenceFile file, String idempotencyKey);

    /** Named by a declaration: from now on the file must be kept (confirm-attached, design §3.2.1). */
    void markNamed(Collection<String> fileIds, Instant namedAt);

    /** Named files whose confirm-attached has not succeeded yet, oldest first. */
    List<String> findNamedNotAttached(int limit);

    void markAttached(String fileId, Instant confirmedAt);

    /**
     * Keeps file-service's scan outcome for {@code fileId}. Returns false when an outcome was already kept (the first
     * one stays).
     */
    boolean recordScanOutcome(String fileId, EvidenceScanStatus status, String failureReason, Instant receivedAt);
}
