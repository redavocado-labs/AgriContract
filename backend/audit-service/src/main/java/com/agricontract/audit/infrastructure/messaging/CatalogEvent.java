package com.agricontract.audit.infrastructure.messaging;

import com.agricontract.audit.domain.model.SubjectType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One event of the audit ingestion catalog (hash-chain §2.4, #255, D98), read from golden-flow-events.yaml as frozen.
 * contract.signed keeps its own consumer (ContractSignedConsumer); everything else is described here and read by
 * {@link CatalogEventConsumer}.
 *
 * @param exchange      the producer's topic exchange; the routing key is the event type
 * @param required      payload keys that must be present (null allowed only when also in {@code nullable})
 * @param optional      payload keys kept when present
 * @param excluded      payload keys never written to the chain (contact data: governance §6 minimisation)
 * @param uuidKeys      keys whose non-null value must be a UUID
 * @param hashKeys      keys whose value must be 64 lowercase hex characters
 * @param sourceHashKey the key copied to source_hash (hash of the source object), or null
 * @param sourceType    the source_type, from the payload when it depends on it (tier, action)
 * @param subjectType   who the record belongs to
 */
public record CatalogEvent(String eventType, String exchange, List<String> required, List<String> optional,
                    Set<String> nullable, Set<String> excluded, Set<String> uuidKeys, Set<String> hashKeys,
                    String sourceHashKey, Function<JsonNode, String> sourceType, SubjectType subjectType) {

    static final String CONTRACTS = "agricontract.contracts";
    static final String INSPECTION = "agricontract.inspection";
    static final String BANK = "agricontract.bank";
    static final String REPUTATION = "agricontract.reputation";

    /** The subject id: the contract, "buyerId:sellerId" for a pair, "SYSTEM" for the system. */
    String subjectId(Map<String, Object> content) {
        return switch (subjectType) {
            case CONTRACT -> (String) content.get("contractId");
            case USER_PAIR -> content.get("buyerId") + ":" + content.get("sellerId");
            case SYSTEM -> "SYSTEM";
        };
    }

    public static final List<CatalogEvent> ALL = List.of(
            contract("milestone.seller_weighed", "MILESTONE_EVENT",
                    List.of("contractId", "milestoneId", "declarationNumber", "sellerDeclaredWeight",
                            "sellerEvidenceFileId"),
                    List.of("deliveryCertificateFileId"), Set.of(),
                    Set.of("contractId", "milestoneId", "sellerEvidenceFileId", "deliveryCertificateFileId"),
                    Set.of(), null),
            contract("milestone.buyer_received", "MILESTONE_EVENT",
                    List.of("contractId", "milestoneId", "buyerReceivedWeight", "buyerEvidenceFileId", "receivedAt"),
                    List.of("deliveryCertificateFileId"), Set.of(),
                    Set.of("contractId", "milestoneId", "buyerEvidenceFileId", "deliveryCertificateFileId"),
                    Set.of(), null),
            contract("milestone.buyer_confirmed", "MILESTONE_EVENT",
                    List.of("contractId", "milestoneId", "confirmedBy"), List.of(), Set.of(),
                    Set.of("contractId", "milestoneId"), Set.of(), null),
            new CatalogEvent("milestone.settled", CONTRACTS,
                    List.of("contractId", "milestoneId", "lockedAmount", "actualAmount", "recipients"), List.of(),
                    Set.of(), Set.of("recipients"), Set.of("contractId", "milestoneId"), Set.of(), null,
                    p -> "MILESTONE_EVENT", SubjectType.CONTRACT),
            contract("milestone.force_majeure_claimed", "MILESTONE_EVENT",
                    List.of("contractId", "milestoneId", "forceMajeureClaimId"), List.of(), Set.of(),
                    Set.of("contractId", "milestoneId", "forceMajeureClaimId"), Set.of(), null),
            contract("milestone.force_majeure_resolved", "MILESTONE_EVENT",
                    List.of("contractId", "milestoneId", "forceMajeureClaimId", "decision"), List.of(), Set.of(),
                    Set.of("contractId", "milestoneId", "forceMajeureClaimId"), Set.of(), null),
            contract("milestone.cancelled_with_penalty", "MILESTONE_EVENT",
                    List.of("contractId", "milestoneId", "remedyDecisionId", "finalBreachingRole",
                            "breachReasonCode", "penaltyAmount"), List.of(), Set.of(),
                    Set.of("contractId", "milestoneId", "remedyDecisionId"), Set.of(), null),
            contract("breach.reported", "BREACH_REPORTED",
                    List.of("breachCaseId", "contractId", "milestoneId", "requestedBy", "allegedBreachingRole",
                            "breachReasonCode", "severity"), List.of(),
                    Set.of("milestoneId", "allegedBreachingRole"),
                    Set.of("breachCaseId", "contractId", "milestoneId"), Set.of(), null),
            contract("remedy.finalized", "REMEDY_FINALIZED",
                    List.of("remedyDecisionId", "attributionDecisionId", "breachCaseId", "contractId", "buyerId",
                            "sellerId", "affectedMilestoneIds", "settledMilestoneCount", "finalBreachingRole",
                            "breachReasonCode", "qualityDisposition", "decisionSource", "penaltyEligible",
                            "reputationEligible", "remedyLegs"), List.of(),
                    Set.of("breachCaseId", "finalBreachingRole", "breachReasonCode", "qualityDisposition"),
                    Set.of("remedyDecisionId", "attributionDecisionId", "breachCaseId", "contractId", "buyerId",
                            "sellerId"), Set.of(), null),
            contract("contract.terminated", "CONTRACT_TERMINATED",
                    List.of("contractId", "terminationType", "requestedBy", "finalBreachingRole", "breachReasonCode",
                            "remedyDecisionId", "breachCaseId", "affectedMilestoneIds", "supersededByContractId",
                            "replacesContractId"), List.of(),
                    Set.of("requestedBy", "finalBreachingRole", "breachReasonCode", "remedyDecisionId",
                            "breachCaseId", "supersededByContractId", "replacesContractId"),
                    Set.of("contractId", "remedyDecisionId", "breachCaseId", "supersededByContractId",
                            "replacesContractId"), Set.of(), null),
            contract("contract.settled", "CONTRACT_SETTLED",
                    List.of("contractId", "buyerId", "sellerId"), List.of(), Set.of(),
                    Set.of("contractId", "buyerId", "sellerId"), Set.of(), null),
            contract("contract.document_generated", "CONTRACT_DOCUMENT_GENERATED",
                    List.of("documentId", "contractId", "documentType", "fileId", "sourceContentHash",
                            "documentFileHash", "templateCode", "templateVersion", "generatedAt"), List.of(), Set.of(),
                    Set.of("documentId", "contractId", "fileId"), Set.of("sourceContentHash", "documentFileHash"),
                    "sourceContentHash"),
            new CatalogEvent("inspection.report_confirmed", INSPECTION,
                    List.of("reportId", "contractId", "milestoneId", "tier", "normalizedResult", "resultHash",
                            "reportFileHash", "reportHash", "confirmedAt"),
                    List.of("inspectorId", "confirmedByOperatorId"), Set.of(), Set.of(),
                    Set.of("reportId", "contractId", "milestoneId", "inspectorId", "confirmedByOperatorId"),
                    Set.of("resultHash", "reportFileHash", "reportHash"), "reportHash",
                    p -> switch (p.path("tier").asText()) {
                        case "LEVEL_1_5" -> "INSPECTION_REPORT";
                        case "LEVEL_2" -> "EXTERNAL_INSPECTION_REPORT";
                        default -> throw new InvalidEventPayloadException("tier is not LEVEL_1_5 or LEVEL_2");
                    }, SubjectType.CONTRACT),
            new CatalogEvent("inspection.level2_commissioned", INSPECTION,
                    List.of("contractId", "commissionId", "org", "requestedAt"), List.of(), Set.of(), Set.of(),
                    Set.of("contractId", "commissionId"), Set.of(), null, p -> "LEVEL2_INSPECTION_COMMISSIONED",
                    SubjectType.CONTRACT),
            new CatalogEvent("bank.verifier_key_registered", BANK, List.of("publicKeyFingerprint"),
                    List.of("rotationSignedByOldKey"), Set.of("rotationSignedByOldKey"), Set.of(), Set.of(),
                    // The fingerprint is not a hex digest (the schema only asks for text): content, not source_hash.
                    Set.of(), null, p -> "EXTERNAL_VERIFIER_KEY_REGISTERED", SubjectType.SYSTEM),
            new CatalogEvent("bank.security_lock_changed", BANK,
                    List.of("action", "verifierOrgId", "reason", "signedPayload"), List.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), null,
                    p -> switch (p.path("action").asText()) {
                        case "LOCKED" -> "SECURITY_LOCK_TRIGGERED";
                        case "UNLOCKED" -> "SECURITY_UNLOCK_TRIGGERED";
                        default -> throw new InvalidEventPayloadException("action is not LOCKED or UNLOCKED");
                    }, SubjectType.SYSTEM),
            new CatalogEvent("bank.suspicious_report_created", BANK,
                    List.of("buyerId", "sellerId", "windowEnd", "reportHash"), List.of(), Set.of(), Set.of(),
                    Set.of("buyerId", "sellerId"), Set.of("reportHash"), "reportHash", p -> "STRUCTURING_REPORT",
                    SubjectType.USER_PAIR),
            new CatalogEvent("reputation.elevated_risk_cleared", REPUTATION,
                    List.of("buyerId", "sellerId", "clearedByArbitratorId", "reason", "proposedByOperatorId",
                            "approvedByArbitratorId"), List.of(), Set.of(), Set.of(),
                    Set.of("buyerId", "sellerId", "clearedByArbitratorId", "proposedByOperatorId",
                            "approvedByArbitratorId"), Set.of(), null, p -> "AML_RISK_CLEARED",
                    SubjectType.USER_PAIR));

    private static CatalogEvent contract(String eventType, String sourceType, List<String> required,
                                         List<String> optional, Set<String> nullable, Set<String> uuidKeys,
                                         Set<String> hashKeys, String sourceHashKey) {
        return new CatalogEvent(eventType, CONTRACTS, required, optional, nullable, Set.of(), uuidKeys, hashKeys,
                sourceHashKey, p -> sourceType, SubjectType.CONTRACT);
    }

    public static String queue(String eventType) {
        return "audit-svc." + eventType;
    }

    static CatalogEvent of(String eventType) {
        return ALL.stream().filter(e -> e.eventType().equals(eventType)).findFirst().orElse(null);
    }
}
