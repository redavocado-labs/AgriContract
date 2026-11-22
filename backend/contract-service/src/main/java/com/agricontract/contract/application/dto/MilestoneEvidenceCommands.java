package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;

import java.math.BigDecimal;
import java.util.UUID;

/** Seller weigh and evidence upload (D71-D75, golden-flow-api uploadMilestoneEvidence, recordSellerWeighed). */
public final class MilestoneEvidenceCommands {
    private MilestoneEvidenceCommands() {
    }

    /** contentLength is the request's Content-Length header, -1 when absent. */
    public record Upload(String contractId, String milestoneId, String actorId, UUID idempotencyKey,
                         String contentType, long contentLength) {
    }

    /** The file and what contract-service knows of its scan. */
    public record Uploaded(String fileId, EvidenceScanStatus status) {
    }

    /** deliveryCertificateFileId may be null. */
    public record Weigh(String contractId, String milestoneId, String actorId, UUID idempotencyKey,
                        BigDecimal sellerDeclaredWeight, String sellerEvidenceFileId,
                        String deliveryCertificateFileId, String statementVersion, String presentedContentHash) {
    }

    /** file.ready / file.failed; failureReason only for FAILED. */
    public record ScanResult(String eventId, String fileId, EvidenceScanStatus status, String failureReason) {
    }
}
