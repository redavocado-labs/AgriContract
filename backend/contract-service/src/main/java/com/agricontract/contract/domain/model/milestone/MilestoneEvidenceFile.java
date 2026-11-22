package com.agricontract.contract.domain.model.milestone;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.MilestoneRequestInvalidException;
import com.agricontract.contract.domain.model.consent.SignerRole;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * A file a party uploaded for one milestone through uploadMilestoneEvidence (D71). Only such a file, uploaded by the
 * acting party for this very milestone and READY, can be named by weigh (and later receive/confirm/flag).
 */
public record MilestoneEvidenceFile(String fileId, String milestoneId, String contractId, String uploadedBy,
                                    SignerRole uploaderRole, Instant uploadedAt, EvidenceScanStatus scanStatus) {

    /**
     * The request part of the file rules (400, design §3.2.1): the two files differ (EVIDENCE_FILE_REUSED) and each
     * was uploaded by {@code actorId} for {@code milestoneId} (EVIDENCE_NOT_UPLOADED). {@code certificateFileId} may
     * be null. {@code known} maps fileId to the recorded file, for the ids asked.
     */
    public static void requireUploadedBy(String evidenceFileId, String certificateFileId, String actorId,
                                         String milestoneId, Map<String, MilestoneEvidenceFile> known) {
        Objects.requireNonNull(evidenceFileId, "evidenceFileId");
        if (evidenceFileId.equals(certificateFileId)) {
            throw new MilestoneRequestInvalidException(MilestoneRequestInvalidException.EVIDENCE_FILE_REUSED,
                    "The weighing photo and the delivery certificate must be two different files.");
        }
        requireUploadedBy(known.get(evidenceFileId), actorId, milestoneId);
        if (certificateFileId != null) {
            requireUploadedBy(known.get(certificateFileId), actorId, milestoneId);
        }
    }

    /**
     * The scan part (409): each named file is READY; EVIDENCE_NOT_READY while it is being scanned (retry shortly),
     * EVIDENCE_REJECTED when the scan failed (upload another). Call after {@link #requireUploadedBy}.
     */
    public static void requireReady(String evidenceFileId, String certificateFileId,
                                    Map<String, MilestoneEvidenceFile> known) {
        requireReady(known.get(evidenceFileId));
        if (certificateFileId != null) {
            requireReady(known.get(certificateFileId));
        }
    }

    /** {@link #requireUploadedBy} for any number of files (the contest evidence of D91); 400 on the first that fails. */
    public static void requireAllUploadedBy(Collection<String> fileIds, String actorId, String milestoneId,
                                            Map<String, MilestoneEvidenceFile> known) {
        for (String fileId : fileIds) {
            requireUploadedBy(known.get(fileId), actorId, milestoneId);
        }
    }

    /** {@link #requireReady} for any number of files (409 on the first that is not READY). */
    public static void requireAllReady(Collection<String> fileIds, Map<String, MilestoneEvidenceFile> known) {
        for (String fileId : fileIds) {
            requireReady(known.get(fileId));
        }
    }

    private static void requireUploadedBy(MilestoneEvidenceFile file, String actorId, String milestoneId) {
        if (file == null || !file.milestoneId().equals(milestoneId) || !file.uploadedBy().equals(actorId)) {
            throw new MilestoneRequestInvalidException(MilestoneRequestInvalidException.EVIDENCE_NOT_UPLOADED,
                    "Upload the file for this milestone first; only your own upload for it can be used.");
        }
    }

    private static void requireReady(MilestoneEvidenceFile file) {
        switch (file.scanStatus()) {
            case READY -> {
            }
            case PROCESSING -> throw new ContractConflictException(ContractConflictException.EVIDENCE_NOT_READY,
                    "File " + file.fileId() + " is still being checked; try again shortly.");
            case FAILED -> throw new ContractConflictException(ContractConflictException.EVIDENCE_REJECTED,
                    "File " + file.fileId() + " was rejected by the security check; upload another one.");
        }
    }
}
