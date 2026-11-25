package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.FundingStatusMail;
import com.agricontract.contract.application.port.MilestoneStatusMail;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.activation.ActivationRecovery;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.milestone.BuyerReceipt;
import com.agricontract.contract.domain.model.milestone.FundingStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneSettlement;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.model.milestone.SellerWeight;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyLeg;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.terms.ContractTermsSnapshot;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * contract.signed (golden-flow-events.yaml contractSigned, D38) into event_outbox, in the verify transaction. The
 * java-eventing poller sends it to agricontract.contracts and resends with the same eventId until confirmed.
 * Consumers (escrow, product, analytics, audit) refuse a payload outside the schema, so it is checked here: a
 * contract that cannot produce a valid event cannot become SIGNED (the verify rolls back) instead of failing later
 * in a DLQ.
 */
@Component
@RequiredArgsConstructor
public class OutboxContractEventPublisher implements ContractEventPublisher {
    static final String PRODUCER = "contract-service";
    static final String CONTRACT_CREATED = "contract.created";
    static final String CONTRACT_SIGNED = "contract.signed";
    static final String CONTRACT_ANCHOR_REQUESTED = "notification.contract_anchor_requested";
    static final String CONTRACT_ACTIVATED = "contract.activated";
    static final String ACTIVATION_FAILED_REQUESTED = "notification.contract_activation_failed_requested";
    static final String DEPOSIT_LOCK_RETRY_REQUESTED = "contract.deposit_lock_retry_requested";
    static final String REMEDY_RETRY_REQUESTED = "contract.remedy_retry_requested";
    static final String CONTRACT_SETTLED = "contract.settled";
    static final String ACTIVATION_REFUND_REQUESTED = "contract.activation_refund_requested";
    static final String ACTIVATION_CANCEL_REQUESTED = "notification.activation_cancel_requested";
    static final String ACTIVATION_FAILED = "contract.activation_failed";
    static final String CONTRACT_WITHDRAWN = "contract.withdrawn";
    static final String CONTRACT_TERMINATED = "contract.terminated";
    static final String CONTRACT_TERMINATED_REQUESTED = "notification.contract_terminated_requested";
    static final String WITHDRAW_OFFER = "WITHDRAW_OFFER";
    static final String MILESTONE_FUNDING_REQUESTED = "milestone.funding_requested";
    static final String MILESTONE_SELLER_WEIGHED = "milestone.seller_weighed";
    static final String MILESTONE_BUYER_RECEIVED = "milestone.buyer_received";
    static final String MILESTONE_SETTLED = "milestone.settled";
    static final String MILESTONE_BUYER_CONFIRMED = "milestone.buyer_confirmed";
    static final String BREACH_REPORTED = "breach.reported";
    static final String INSPECTION_LEVEL1_5_REQUESTED = "inspection.level1_5_requested";
    static final String INSPECTION_LEVEL1_5_CANCELLED = "inspection.level1_5_cancelled";
    static final String MILESTONE_DISPUTE_RESOLVED = "milestone.dispute_resolved";
    static final String REMEDY_FINALIZED = "remedy.finalized";
    static final String MILESTONE_STATUS_REQUESTED = "notification.milestone_status_requested";
    static final String BREACH_NOTICE_REQUESTED = "notification.breach_notice_requested";
    static final String REMEDY_FINALIZED_REQUESTED = "notification.remedy_finalized_requested";
    static final String MILESTONE_FUNDING_STATUS_REQUESTED = "notification.milestone_funding_status_requested";

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    /**
     * contract.created (golden-flow-events.yaml contractCreated, design §2.1d): product-service learns that the
     * reservation it took for this contractId belongs to a persisted OFFERED contract. It never decrements again.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractCreated(Contract contract, Instant createdAt) {
        if (contract.getStatus() != ContractStatus.OFFERED || contract.getCoffeeTerms() == null) {
            throw new IllegalStateException("contract.created for contract " + contract.getContractId().value()
                    + " in status " + contract.getStatus());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("listingId", uuid(contract.getListingId(), "listingId"));
        payload.put("reservedQuantity", contract.getReservedQuantity());
        payload.put("createdAt", createdAt.toString());

        // Root of the contract's event chain: correlationId = contractId, no causationId.
        outbox.append(new EventEnvelope(UUID.randomUUID(), CONTRACT_CREATED, 1, createdAt, PRODUCER, contractId,
                contractId, null, null, payload));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractSigned(Contract contract, Instant signedAt) {
        if (contract.getStatus() != ContractStatus.SIGNED || contract.getCoffeeTerms() == null) {
            throw new IllegalStateException("contract.signed for contract " + contract.getContractId().value()
                    + " in status " + contract.getStatus());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        ContractTermsSnapshot terms = contract.getCoffeeTerms().snapshot();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("listingId", uuid(contract.getListingId(), "listingId"));
        payload.put("reservedQuantity", contract.getReservedQuantity());
        payload.put("commodity", "COFFEE");
        payload.put("buyerId", uuid(contract.getBuyerId(), "buyerId"));
        payload.put("sellerId", uuid(contract.getSellerId(), "sellerId"));
        payload.put("totalAmount", terms.totalContractValue().wire());
        payload.put("buyerDepositAmount", terms.buyerDepositAmount().wire());
        payload.put("sellerDepositAmount", terms.sellerDepositAmount().wire());
        payload.put("signedContentHash", contract.getCoffeeTerms().signedContentHash());
        payload.put("signedAt", signedAt.toString());

        // correlationId = contractId: escrow carries it into the deposit lock chain, so every event of one
        // contract can be joined in the logs. Root event: no causationId.
        outbox.append(new EventEnvelope(UUID.randomUUID(), CONTRACT_SIGNED, 1, signedAt, PRODUCER, contractId,
                contractId, null, null, payload));
    }

    /**
     * notification-commands.yaml contractAnchorRequested. Recipients are the buyer and seller emails stored on the
     * contract at offer time (D38); the snapshot is the exact canonical JSON the parties signed, as an object.
     * Routed by its eventType on agricontract.contracts, like every command (notification design §2.1).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractAnchorRequested(Contract contract, Instant signedAt) {
        if (contract.getStatus() != ContractStatus.SIGNED || contract.getCoffeeTerms() == null) {
            throw new IllegalStateException("contract_anchor_requested for contract "
                    + contract.getContractId().value() + " in status " + contract.getStatus());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("recipients", List.of(
                recipient(contract.getBuyerId(), contract.getBuyerEmail(), "BUYER"),
                recipient(contract.getSellerId(), contract.getSellerEmail(), "SELLER")));
        payload.put("contractTermsSnapshot", snapshot(contract));
        payload.put("signedContentHash", contract.getCoffeeTerms().signedContentHash());
        payload.put("signedAt", signedAt.toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), CONTRACT_ANCHOR_REQUESTED, 1, signedAt, PRODUCER,
                contractId, contractId, null, null, payload));
    }

    /**
     * contract.activated (golden-flow-events.yaml contractActivated): product-service moves the reservation from
     * SIGNED_RELEASABLE to COMMITTED.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractActivated(Contract contract, Instant activatedAt, String correlationId, String causationId) {
        if (contract.getStatus() != ContractStatus.ACTIVE || contract.getCoffeeTerms() == null) {
            throw new IllegalStateException("contract.activated for contract " + contract.getContractId().value()
                    + " in status " + contract.getStatus());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("listingId", uuid(contract.getListingId(), "listingId"));
        payload.put("reservedQuantity", contract.getReservedQuantity());
        payload.put("activatedAt", activatedAt.toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), CONTRACT_ACTIVATED, 1, activatedAt, PRODUCER, contractId,
                correlationId, causationId, null, payload));
    }

    /**
     * notification-commands.yaml contractActivationFailedRequested. Recipients are the parties' emails stored on the
     * contract; OPERATOR is reached by notification-service's own operator address (notification design §8).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractActivationFailedRequested(Contract contract, ActivationRecovery recovery,
                                                  String buyerDepositState, String sellerDepositState,
                                                  String correlationId, String causationId) {
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("recipients", List.of(
                recipient(contract.getBuyerId(), contract.getBuyerEmail(), "BUYER"),
                recipient(contract.getSellerId(), contract.getSellerEmail(), "SELLER")));
        payload.put("failedLeg", recovery.lastFailedLeg().name());
        payload.put("buyerDepositState", buyerDepositState);
        payload.put("sellerDepositState", sellerDepositState);
        payload.put("nextActions", List.of("RETRY_DEPOSIT_LOCK", "REQUEST_ACTIVATION_CANCEL"));
        payload.put("oneSidedCancelAllowedFrom", recovery.oneSidedCancelAllowedFrom().toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), ACTIVATION_FAILED_REQUESTED, 1, recovery.lastFailedAt(),
                PRODUCER, contractId, correlationId, causationId, null, payload));
    }

    /** golden-flow-events.yaml contractDepositLockRetryRequested, to escrow-service. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void depositLockRetryRequested(Contract contract, String operatorUserId, Instant requestedAt) {
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("operatorUserId", uuid(operatorUserId, "operatorUserId"));
        payload.put("requestedAt", requestedAt.toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), DEPOSIT_LOCK_RETRY_REQUESTED, 1, requestedAt, PRODUCER,
                contractId, contractId, null, null, payload));
    }

    /** golden-flow-events.yaml contractRemedyRetryRequested (D93), to escrow-service. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void remedyRetryRequested(Contract contract, String operatorUserId, Instant requestedAt) {
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("operatorUserId", uuid(operatorUserId, "operatorUserId"));
        payload.put("requestedAt", requestedAt.toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), REMEDY_RETRY_REQUESTED, 1, requestedAt, PRODUCER,
                contractId, contractId, null, null, payload));
    }

    /**
     * golden-flow-events.yaml contractSettled (D93): lifecycle and positive history only, never money. Written only by
     * the commit of a SETTLED contract on escrow.contract_lock_cleared.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractSettled(Contract contract, Instant settledAt, String correlationId, String causationId) {
        if (contract.getStatus() != ContractStatus.SETTLED) {
            throw new IllegalStateException("contract.settled for contract " + contract.getContractId().value()
                    + " in status " + contract.getStatus());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("buyerId", uuid(contract.getBuyerId(), "buyerId"));
        payload.put("sellerId", uuid(contract.getSellerId(), "sellerId"));
        outbox.append(new EventEnvelope(UUID.randomUUID(), CONTRACT_SETTLED, 1, settledAt, PRODUCER, contractId,
                correlationId, causationId, null, payload));
    }

    /** golden-flow-events.yaml contractActivationRefundRequested, to escrow-service. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void activationRefundRequested(Contract contract, List<SignerRole> requestedBy, String trigger,
                                          Instant at) {
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("requestedBy", requestedBy.stream().map(Enum::name).toList());
        payload.put("trigger", trigger);
        payload.put("requestedAt", at.toString());
        outbox.append(new EventEnvelope(UUID.randomUUID(), ACTIVATION_REFUND_REQUESTED, 1, at, PRODUCER, contractId,
                contractId, null, null, payload));
    }

    /** notification-commands.yaml activationCancelRequested: to the other party (OPERATOR via its own address). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void activationCancelRequested(Contract contract, SignerRole requestedBy, Instant oneSidedCancelAllowedFrom,
                                          Instant at) {
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("recipients", List.of(requestedBy == SignerRole.BUYER
                ? recipient(contract.getSellerId(), contract.getSellerEmail(), "SELLER")
                : recipient(contract.getBuyerId(), contract.getBuyerEmail(), "BUYER")));
        payload.put("requestedByRole", requestedBy.name());
        payload.put("oneSidedCancelAllowedFrom", oneSidedCancelAllowedFrom.toString());
        outbox.append(new EventEnvelope(UUID.randomUUID(), ACTIVATION_CANCEL_REQUESTED, 1, at, PRODUCER, contractId,
                contractId, null, null, payload));
    }

    /** golden-flow-events.yaml contractActivationFailed, after every deposit came back (lock = 0). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractActivationFailed(Contract contract, Instant failedAt, String correlationId,
                                         String causationId) {
        if (contract.getStatus() != ContractStatus.ACTIVATION_FAILED || contract.getCoffeeTerms() == null) {
            throw new IllegalStateException("contract.activation_failed for contract "
                    + contract.getContractId().value() + " in status " + contract.getStatus());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("listingId", uuid(contract.getListingId(), "listingId"));
        payload.put("reservedQuantity", contract.getReservedQuantity());
        payload.put("activationFailedAt", failedAt.toString());
        outbox.append(new EventEnvelope(UUID.randomUUID(), ACTIVATION_FAILED, 1, failedAt, PRODUCER, contractId,
                correlationId, causationId, null, payload));
    }

    /**
     * golden-flow-events.yaml contractWithdrawn: product-service releases the quantity it reserved for this contract.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void contractWithdrawn(Contract contract, Instant withdrawnAt) {
        requireWithdrawn(contract, CONTRACT_WITHDRAWN);
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("listingId", uuid(contract.getListingId(), "listingId"));
        payload.put("reservedQuantity", contract.getReservedQuantity());
        payload.put("withdrawnAt", withdrawnAt.toString());
        outbox.append(new EventEnvelope(UUID.randomUUID(), CONTRACT_WITHDRAWN, 1, withdrawnAt, PRODUCER, contractId,
                contractId, null, null, payload));
    }

    /** golden-flow-events.yaml contractTerminated, WITHDRAW_OFFER: every breach/remedy/supersede field is null. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void offerWithdrawnTerminated(Contract contract, SignerRole requestedBy, Instant withdrawnAt) {
        requireWithdrawn(contract, CONTRACT_TERMINATED);
        String contractId = uuid(contract.getContractId().value(), "contractId");
        outbox.append(new EventEnvelope(UUID.randomUUID(), CONTRACT_TERMINATED, 1, withdrawnAt, PRODUCER, contractId,
                contractId, null, null, withdrawOfferTermination(contractId, requestedBy)));
    }

    /** notification-commands.yaml contractTerminatedRequested, WITHDRAW_OFFER: to the other party only, no legs. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void offerWithdrawnNoticeRequested(Contract contract, SignerRole requestedBy, Instant withdrawnAt) {
        requireWithdrawn(contract, CONTRACT_TERMINATED_REQUESTED);
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> termination = withdrawOfferTermination(contractId, requestedBy);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("recipients", List.of(requestedBy == SignerRole.BUYER
                ? recipient(contract.getSellerId(), contract.getSellerEmail(), "SELLER")
                : recipient(contract.getBuyerId(), contract.getBuyerEmail(), "BUYER")));
        termination.remove("contractId");
        payload.putAll(termination);
        payload.put("remedyLegs", List.of());
        outbox.append(new EventEnvelope(UUID.randomUUID(), CONTRACT_TERMINATED_REQUESTED, 1, withdrawnAt, PRODUCER,
                contractId, contractId, null, null, payload));
    }

    private static Map<String, Object> withdrawOfferTermination(String contractId, SignerRole requestedBy) {
        // Nullable fields are still on the wire (milestone-escrow design §7.2).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("terminationType", WITHDRAW_OFFER);
        payload.put("requestedBy", requestedBy.name());
        payload.put("finalBreachingRole", null);
        payload.put("breachReasonCode", null);
        payload.put("remedyDecisionId", null);
        payload.put("breachCaseId", null);
        payload.put("affectedMilestoneIds", List.of());
        payload.put("supersededByContractId", null);
        payload.put("replacesContractId", null);
        return payload;
    }

    private static void requireWithdrawn(Contract contract, String eventType) {
        if (contract.getStatus() != ContractStatus.WITHDRAWN || contract.getCoffeeTerms() == null) {
            throw new IllegalStateException(eventType + " for contract " + contract.getContractId().value()
                    + " in status " + contract.getStatus());
        }
    }

    private static Map<String, Object> recipient(String userId, String email, String role) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("a notification command needs the " + role + " email");
        }
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("userId", uuid(userId, role.toLowerCase() + "Id"));
        recipient.put("email", email);
        recipient.put("role", role);
        return recipient;
    }

    private Map<String, Object> snapshot(Contract contract) {
        try {
            // BigDecimal, not double: the numbers must reach the email exactly as signed.
            return objectMapper.readerFor(new TypeReference<Map<String, Object>>() {})
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(contract.getCoffeeTerms().canonicalJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored terms of " + contract.getContractId().value()
                    + " are not a JSON object", e);
        }
    }

    private static String uuid(String value, String field) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw new IllegalStateException("contract.signed needs " + field + " as a UUID, got " + value);
        }
        return value;
    }

    /**
     * golden-flow-events.yaml milestoneFundingRequested, to escrow-service on agricontract.contracts. batchAmount is
     * already rounded to the whole đồng (D65); escrow locks exactly it.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneFundingRequested(Contract contract, Milestone milestone, String correlationId,
                                          String causationId) {
        if (milestone.fundingStatus() != FundingStatus.FUNDING_PENDING || milestone.batchAmount() == null
                || !milestone.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException("milestone.funding_requested for milestone " + milestone.milestoneId()
                    + " in funding status " + milestone.fundingStatus());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", uuid(milestone.milestoneId(), "milestoneId"));
        payload.put("milestoneIndex", milestone.milestoneIndex());
        payload.put("buyerId", uuid(contract.getBuyerId(), "buyerId"));
        payload.put("batchAmount", milestone.batchAmount().wire());
        payload.put("requestedAt", milestone.fundingRequestedAt().toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), MILESTONE_FUNDING_REQUESTED, 1,
                milestone.fundingRequestedAt(), PRODUCER, contractId,
                correlationId != null ? correlationId : contractId, causationId, null, payload));
    }

    /**
     * golden-flow-events.yaml milestoneSellerWeighed, on agricontract.contracts. The weight is a JSON number with no
     * trailing zeros; deliveryCertificateFileId is left out when the seller attached none (the schema has no null).
     * aggregateId is the milestone, the aggregate that changed; correlationId keeps the contract's chain.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneSellerWeighed(Contract contract, Milestone milestone, SellerWeighDeclaration declaration) {
        if (milestone.status() != MilestoneStatus.SELLER_WEIGHED
                || !milestone.contractId().equals(contract.getContractId().value())
                || !declaration.milestoneId().equals(milestone.milestoneId())) {
            throw new IllegalStateException("milestone.seller_weighed for milestone " + milestone.milestoneId()
                    + " in status " + milestone.status());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("declarationNumber", declaration.declarationNumber());
        payload.put("sellerDeclaredWeight", new BigDecimal(SellerWeight.plain(declaration.declaredWeight())));
        payload.put("sellerEvidenceFileId", uuid(declaration.evidenceFileId(), "sellerEvidenceFileId"));
        if (declaration.deliveryCertificateFileId() != null) {
            payload.put("deliveryCertificateFileId",
                    uuid(declaration.deliveryCertificateFileId(), "deliveryCertificateFileId"));
        }

        outbox.append(new EventEnvelope(UUID.randomUUID(), MILESTONE_SELLER_WEIGHED, 1, declaration.declaredAt(),
                PRODUCER, milestoneId, contractId, null, null, payload));
    }

    /**
     * golden-flow-events.yaml milestoneBuyerReceived, on agricontract.contracts. Same shape rules as seller_weighed:
     * the weight is a JSON number with no trailing zeros, deliveryCertificateFileId is left out when none was attached.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneBuyerReceived(Contract contract, Milestone milestone) {
        BuyerReceipt receipt = milestone.buyerReceipt();
        if (milestone.status() != MilestoneStatus.BUYER_RECEIVED || receipt == null
                || !milestone.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException("milestone.buyer_received for milestone " + milestone.milestoneId()
                    + " in status " + milestone.status());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("buyerReceivedWeight", new BigDecimal(SellerWeight.plain(receipt.receivedWeight())));
        payload.put("buyerEvidenceFileId", uuid(receipt.evidenceFileId(), "buyerEvidenceFileId"));
        if (receipt.certificateFileId() != null) {
            payload.put("deliveryCertificateFileId", uuid(receipt.certificateFileId(), "deliveryCertificateFileId"));
        }
        payload.put("receivedAt", receipt.receivedAt().toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), MILESTONE_BUYER_RECEIVED, 1, receipt.receivedAt(),
                PRODUCER, milestoneId, contractId, null, null, payload));
    }

    /**
     * golden-flow-events.yaml milestoneSettled, to escrow-service on agricontract.contracts. Money is the whole-dong
     * string ("487500000.00"), as batchAmount in milestone.funding_requested; lockedAmount is the batch, actualAmount
     * what the seller earns (0.00 is allowed), the rest goes back to the buyer.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneSettled(Contract contract, Milestone milestone) {
        MilestoneSettlement settlement = requireSettled(contract, milestone, MILESTONE_SETTLED);
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("lockedAmount", milestone.batchAmount().wire());
        payload.put("actualAmount", settlement.sellerAmount().wire());
        payload.put("recipients", List.of(
                recipient(contract.getBuyerId(), contract.getBuyerEmail(), "BUYER"),
                recipient(contract.getSellerId(), contract.getSellerEmail(), "SELLER")));

        outbox.append(new EventEnvelope(UUID.randomUUID(), MILESTONE_SETTLED, 1, settlement.settledAt(), PRODUCER,
                milestoneId, contractId, null, null, payload));
    }

    /** golden-flow-events.yaml milestoneBuyerConfirmed, on agricontract.contracts. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneBuyerConfirmed(Contract contract, Milestone milestone, String confirmedBy) {
        MilestoneSettlement settlement = requireSettled(contract, milestone, MILESTONE_BUYER_CONFIRMED);
        if (!"BUYER".equals(confirmedBy) && !"SYSTEM_TIMEOUT".equals(confirmedBy)) {
            throw new IllegalStateException("milestone.buyer_confirmed confirmedBy " + confirmedBy);
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("confirmedBy", confirmedBy);

        outbox.append(new EventEnvelope(UUID.randomUUID(), MILESTONE_BUYER_CONFIRMED, 1, settlement.settledAt(),
                PRODUCER, milestoneId, contractId, null, null, payload));
    }

    /** golden-flow-events.yaml breachReported: the allegation only; aggregateId is the BreachCase. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void breachReported(BreachCase breachCase) {
        String breachCaseId = uuid(breachCase.breachCaseId(), "breachCaseId");
        String contractId = uuid(breachCase.contractId(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("breachCaseId", breachCaseId);
        payload.put("contractId", contractId);
        payload.put("milestoneId", breachCase.milestoneId() == null ? null : uuid(breachCase.milestoneId(), "milestoneId"));
        payload.put("requestedBy", breachCase.requestedBy().name());
        payload.put("allegedBreachingRole",
                breachCase.allegedBreachingRole() == null ? null : breachCase.allegedBreachingRole().name());
        payload.put("breachReasonCode", breachCase.breachReasonCode().name());
        payload.put("severity", breachCase.severity().name());

        outbox.append(new EventEnvelope(UUID.randomUUID(), BREACH_REPORTED, 1, breachCase.reportedAt(), PRODUCER,
                breachCaseId, contractId, null, null, payload));
    }

    /** golden-flow-events.yaml inspectionLevel15Requested, a command to inspection-service (DISPUTE_ROUTED or MANDATORY_INSPECTION). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void inspectionLevel15Requested(Contract contract, Milestone milestone, InspectionRequestReason reason,
                                           String initiatedByActorId, Instant at) {
        if (!milestone.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException("inspection.level1_5_requested for milestone " + milestone.milestoneId()
                    + " of another contract");
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("reason", reason.name());
        payload.put("reportDueBusinessDays", contract.getCoffeeTerms().snapshot().negotiated().inspectionTerms()
                .reportDueBusinessDays());
        payload.put("initiatedByActorId",
                initiatedByActorId == null ? null : uuid(initiatedByActorId, "initiatedByActorId"));
        payload.put("requestedAt", at.toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), INSPECTION_LEVEL1_5_REQUESTED, 1, at, PRODUCER,
                milestoneId, contractId, null, null, payload));
    }

    /** golden-flow-events.yaml inspectionLevel15Cancelled, a command to inspection-service (D57). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void inspectionLevel15Cancelled(Contract contract, Milestone milestone, String reason, Instant at) {
        if (!milestone.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException("inspection.level1_5_cancelled for milestone " + milestone.milestoneId()
                    + " of another contract");
        }
        if (!"DISPUTE_CONCEDED".equals(reason) && !"DISPUTE_SETTLED".equals(reason)) {
            throw new IllegalArgumentException("inspection.level1_5_cancelled reason " + reason);
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("reason", reason);
        payload.put("cancelledAt", at.toString());

        outbox.append(new EventEnvelope(UUID.randomUUID(), INSPECTION_LEVEL1_5_CANCELLED, 1, at, PRODUCER,
                milestoneId, contractId, null, null, payload));
    }

    /** golden-flow-events.yaml milestoneDisputeResolved, to reputation-service: only the silent QUANTITY path, for the buyer. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneDisputeResolved(Contract contract, Milestone milestone, SignerRole resolutionFavors) {
        MilestoneSettlement settlement = requireSettled(contract, milestone, MILESTONE_DISPUTE_RESOLVED);
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("flaggedBy", "BUYER");
        payload.put("flaggedByUserId", uuid(contract.getBuyerId(), "flaggedByUserId"));
        payload.put("resolutionFavors", resolutionFavors.name());

        outbox.append(new EventEnvelope(UUID.randomUUID(), MILESTONE_DISPUTE_RESOLVED, 1, settlement.settledAt(),
                PRODUCER, milestoneId, contractId, null, null, payload));
    }

    /**
     * golden-flow-events.yaml remedyFinalized: the legs of a FINALIZED RemedyDecision, on a milestone dispute (#137)
     * or on the whole contract for its normal completion (#238, D94: no breach case, affectedMilestoneIds empty).
     * attributionDecisionId is the BreachCase id when there is a case, else the decision's own id (D94).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void remedyFinalized(Contract contract, RemedyDecision decision, Instant at) {
        Map<String, Object> payload = remedyPayload(contract, decision, REMEDY_FINALIZED);
        outbox.append(new EventEnvelope(UUID.randomUUID(), REMEDY_FINALIZED, 1, at, PRODUCER,
                (String) payload.get("remedyDecisionId"), (String) payload.get("contractId"), null, null, payload));
    }

    /**
     * notification-commands.yaml remedyFinalizedRequested (D96): the decision of remedy.finalized as it went out,
     * without settledMilestoneCount (not in the command), with both parties as recipients.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void remedyFinalizedRequested(Contract contract, RemedyDecision decision, Instant at) {
        Map<String, Object> payload = remedyPayload(contract, decision, REMEDY_FINALIZED_REQUESTED);
        payload.remove("settledMilestoneCount");
        Map<String, Object> ordered = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            ordered.put(k, v);
            if ("affectedMilestoneIds".equals(k)) {
                ordered.put("recipients", parties(contract, List.of(SignerRole.BUYER, SignerRole.SELLER)));
            }
        });
        outbox.append(new EventEnvelope(UUID.randomUUID(), REMEDY_FINALIZED_REQUESTED, 1, at, PRODUCER,
                (String) payload.get("remedyDecisionId"), (String) payload.get("contractId"), null, null, ordered));
    }

    private Map<String, Object> remedyPayload(Contract contract, RemedyDecision decision, String type) {
        if (decision.status() != RemedyStatus.FINALIZED
                || (decision.breachCaseId() == null) != decision.contractLevel()
                || !decision.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException(type + " for remedy decision " + decision.remedyDecisionId()
                    + " in status " + decision.status());
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String decisionId = uuid(decision.remedyDecisionId(), "remedyDecisionId");
        String breachCaseId = decision.breachCaseId() == null ? null : uuid(decision.breachCaseId(), "breachCaseId");
        List<Map<String, Object>> legs = new java.util.ArrayList<>();
        for (RemedyLeg leg : decision.legs()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("remedyLegId", uuid(leg.remedyLegId(), "remedyLegId"));
            l.put("remedyType", leg.remedyType().name());
            l.put("fundType", leg.fundType().name());
            l.put("role", leg.role().name());
            l.put("amount", leg.amount().wire());
            legs.add(l);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("remedyDecisionId", decisionId);
        payload.put("attributionDecisionId", uuid(decision.attributionDecisionId(), "attributionDecisionId"));
        payload.put("breachCaseId", breachCaseId);
        payload.put("contractId", contractId);
        payload.put("buyerId", uuid(contract.getBuyerId(), "buyerId"));
        payload.put("sellerId", uuid(contract.getSellerId(), "sellerId"));
        payload.put("affectedMilestoneIds",
                decision.contractLevel() ? List.of() : List.of(uuid(decision.milestoneId(), "milestoneId")));
        payload.put("settledMilestoneCount", decision.settledMilestoneCount());
        payload.put("finalBreachingRole",
                decision.finalBreachingRole() == null ? null : decision.finalBreachingRole().name());
        payload.put("breachReasonCode", decision.breachReasonCode() == null ? null : decision.breachReasonCode().name());
        payload.put("qualityDisposition",
                decision.qualityDisposition() == null ? null : decision.qualityDisposition().name());
        payload.put("decisionSource", decision.decisionSource().name());
        payload.put("penaltyEligible", decision.penaltyEligible());
        payload.put("reputationEligible", decision.reputationEligible());
        payload.put("remedyLegs", legs);

        return payload;
    }

    /** notification-commands.yaml milestoneStatusRequested (D96); statusData is what the template shows. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneStatusRequested(Contract contract, Milestone milestone, MilestoneStatusMail status,
                                         Instant deadline, Instant at) {
        if (!milestone.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException(MILESTONE_STATUS_REQUESTED + " for milestone " + milestone.milestoneId()
                    + " of another contract");
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("milestoneIndex", milestone.milestoneIndex());
        data.put("committedQuantityKg", SellerWeight.plain(milestone.committedQuantity()));
        switch (status) {
            case WEIGHED, BUYER_RECEIVE_REMINDER -> {
                if (milestone.sellerWeighing() == null) {
                    throw new IllegalStateException(status + " for milestone " + milestoneId + " not weighed");
                }
                data.put("declarationNumber", milestone.sellerWeighing().declarationCount());
                data.put("sellerDeclaredWeightKg", SellerWeight.plain(milestone.sellerWeighing().declaredWeight()));
            }
            case CONFIRMED -> {
                MilestoneSettlement settlement = requireSettled(contract, milestone, MILESTONE_STATUS_REQUESTED);
                data.put("settlementPath", settlement.path().name());
                data.put("billableQuantityKg", SellerWeight.plain(settlement.billableQuantityKg()));
                data.put("sellerAmount", settlement.sellerAmount().wire());
                data.put("buyerRefundAmount", settlement.buyerRefundAmount().wire());
            }
            case FLAGGED -> {
                if (milestone.flag() == null) {
                    throw new IllegalStateException("FLAGGED for milestone " + milestoneId + " with no flag");
                }
                data.put("issueType", milestone.flag().issueType().name());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("recipients", parties(contract, status.recipients()));
        payload.put("statusType", status.name());
        payload.put("deadline", deadline == null ? null : deadline.toString());
        payload.put("statusData", data);
        outbox.append(new EventEnvelope(UUID.randomUUID(), MILESTONE_STATUS_REQUESTED, 1, at, PRODUCER, milestoneId,
                contractId, null, null, payload));
    }

    /** notification-commands.yaml breachNoticeRequested (D96): both parties, evidenceRefs = the case's files. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void breachNoticeRequested(Contract contract, BreachCase breachCase) {
        if (!breachCase.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException(BREACH_NOTICE_REQUESTED + " for case " + breachCase.breachCaseId()
                    + " of another contract");
        }
        String breachCaseId = uuid(breachCase.breachCaseId(), "breachCaseId");
        String contractId = uuid(contract.getContractId().value(), "contractId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("breachCaseId", breachCaseId);
        payload.put("contractId", contractId);
        payload.put("recipients", parties(contract, List.of(SignerRole.BUYER, SignerRole.SELLER)));
        payload.put("breachReasonCode", breachCase.breachReasonCode().name());
        payload.put("severity", breachCase.severity().name());
        payload.put("evidenceRefs", List.copyOf(breachCase.evidenceFileIds()));
        outbox.append(new EventEnvelope(UUID.randomUUID(), BREACH_NOTICE_REQUESTED, 1, breachCase.reportedAt(),
                PRODUCER, breachCaseId, contractId, null, null, payload));
    }

    /** notification-commands.yaml milestoneFundingStatusRequested (D96). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void milestoneFundingStatusRequested(Contract contract, Milestone milestone, FundingStatusMail status,
                                                Instant deadline, String reason, String operatorEmail, Instant at) {
        if (!milestone.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException(MILESTONE_FUNDING_STATUS_REQUESTED + " for milestone "
                    + milestone.milestoneId() + " of another contract");
        }
        String contractId = uuid(contract.getContractId().value(), "contractId");
        String milestoneId = uuid(milestone.milestoneId(), "milestoneId");
        List<Map<String, Object>> recipients = new java.util.ArrayList<>(parties(contract, status.recipients()));
        if (status.operator() && operatorEmail != null && !operatorEmail.isBlank()) {
            Map<String, Object> operator = new LinkedHashMap<>();
            operator.put("userId", null);
            operator.put("email", operatorEmail);
            operator.put("role", "OPERATOR");
            recipients.add(operator);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("milestoneId", milestoneId);
        payload.put("recipients", recipients);
        payload.put("statusType", status.name());
        payload.put("deadline", deadline == null ? null : deadline.toString());
        payload.put("reason", reason);
        outbox.append(new EventEnvelope(UUID.randomUUID(), MILESTONE_FUNDING_STATUS_REQUESTED, 1, at, PRODUCER,
                milestoneId, contractId, null, null, payload));
    }

    private static List<Map<String, Object>> parties(Contract contract, List<SignerRole> roles) {
        return roles.stream().map(role -> role == SignerRole.BUYER
                ? recipient(contract.getBuyerId(), contract.getBuyerEmail(), "BUYER")
                : recipient(contract.getSellerId(), contract.getSellerEmail(), "SELLER")).toList();
    }

    private static MilestoneSettlement requireSettled(Contract contract, Milestone milestone, String eventType) {
        MilestoneSettlement settlement = milestone.settlement();
        if (milestone.status() != MilestoneStatus.SETTLED || settlement == null || milestone.batchAmount() == null
                || !milestone.contractId().equals(contract.getContractId().value())) {
            throw new IllegalStateException(eventType + " for milestone " + milestone.milestoneId()
                    + " in status " + milestone.status());
        }
        return settlement;
    }
}
