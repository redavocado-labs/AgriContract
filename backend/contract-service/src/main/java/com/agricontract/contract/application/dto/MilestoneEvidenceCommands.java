package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;

import com.agricontract.contract.domain.model.milestone.FlagIssueType;

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

    /** recordBuyerReceipt (D76); deliveryCertificateFileId may be null. */
    public record Receive(String contractId, String milestoneId, String actorId, UUID idempotencyKey,
                          BigDecimal buyerReceivedWeight, String buyerEvidenceFileId,
                          String deliveryCertificateFileId, String statementVersion, String presentedContentHash) {
    }

    /** confirmMilestoneClean (D77): the buyer confirms the receipt; the consent only. */
    public record Confirm(String contractId, String milestoneId, String actorId, UUID idempotencyKey,
                          String statementVersion, String presentedContentHash) {
    }

    /** flagMilestoneIssue (D80): the buyer flags the receipt; issueType comes from the body, next to the consent. */
    public record Flag(String contractId, String milestoneId, String actorId, UUID idempotencyKey,
                       FlagIssueType issueType, String statementVersion, String presentedContentHash) {
    }

    /** respondToMilestoneFlag (D80): the seller contests the flag; the consent only. */
    public record Respond(String contractId, String milestoneId, String actorId, UUID idempotencyKey,
                          String statementVersion, String presentedContentHash) {
    }

    /** file.ready / file.failed; failureReason only for FAILED. */
    public record ScanResult(String eventId, String fileId, EvidenceScanStatus status, String failureReason) {
    }
}
