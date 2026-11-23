package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.EvidenceAttachment;
import com.agricontract.contract.application.service.MilestoneCleanSettlement;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.consent.ConsentEvidence;
import com.agricontract.contract.domain.model.consent.OperationalConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.model.milestone.BuyerReceiptConsent;
import com.agricontract.contract.domain.model.milestone.SellerWeight;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ConsentEvidenceRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.application.service.Level15Inspections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * recordBuyerReceipt (D76 step 1, design §3.2.2). One transaction under the contract and milestone locks: party (403),
 * milestone of the contract (404), the Idempotency-Key (a repeat is a no-op 204), the request and its files (400), the
 * contract and milestone state, the files' scan and the consent (409). Then the milestone holds the receipt (and the
 * seller's correction window is closed), the consent is appended, the files are marked named and
 * milestone.buyer_received is written to the outbox. After the commit the named files are confirmed to file-service
 * (best effort; a job retries). There is no correction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordBuyerReceiptUseCase {
    static final String OPERATION = "RECORD_BUYER_RECEIPT";

    private final ContractRepository contracts;
    private final MilestoneRepository milestones;
    private final MilestoneEvidenceRepository evidence;
    private final ConsentEvidenceRepository consents;
    private final OperationalConsentStatements statements;
    private final IdempotencyStore idempotencyStore;
    private final ContractEventPublisher events;
    private final Level15Inspections level15;
    private final EvidenceAttachment attachment;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void execute(MilestoneEvidenceCommands.Receive command) {
        List<String> named = transactions.inTransaction(() -> receive(command));
        attachment.confirmNow(named);
    }

    private List<String> receive(MilestoneEvidenceCommands.Receive command) {
        // Contract lock first, as every contract-service write does, then the milestone.
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        if (contract.roleOf(command.actorId()).filter(role -> role == SignerRole.BUYER).isEmpty()) {
            throw new UnauthorizedContractAccessException("Only the buyer of this contract can record the receipt.");
        }
        Milestone milestone = milestones.findByIdForUpdate(command.milestoneId())
                .filter(m -> m.contractId().equals(command.contractId()))
                .orElseThrow(() -> new MilestoneNotFoundException(command.contractId(), command.milestoneId()));
        // 400 before the key: an invalid request never succeeded, so it has nothing to replay.
        SellerWeight.requireValid(command.buyerReceivedWeight());
        IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.actorId(), OPERATION,
                command.idempotencyKey(), requestHash(command));
        if (outcome == IdempotencyStore.Outcome.CONFLICT) {
            throw new IdempotencyKeyReusedException();
        }
        if (outcome == IdempotencyStore.Outcome.REPLAY) {
            return List.of();
        }

        // 400: the files.
        List<String> fileIds = new ArrayList<>();
        fileIds.add(command.buyerEvidenceFileId());
        if (command.deliveryCertificateFileId() != null) {
            fileIds.add(command.deliveryCertificateFileId());
        }
        Map<String, MilestoneEvidenceFile> known = evidence.findByIds(fileIds);
        MilestoneEvidenceFile.requireUploadedBy(command.buyerEvidenceFileId(), command.deliveryCertificateFileId(),
                command.actorId(), milestone.milestoneId(), known);

        // 409: contract and milestone state, then the files' scan, then the consent. A refusal rolls everything back,
        // the milestone changed in memory included.
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The contract is " + contract.getStatus() + "; the receipt can only be recorded while it is ACTIVE.");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        milestone.recordBuyerReceipt(command.buyerReceivedWeight(), command.buyerEvidenceFileId(),
                command.deliveryCertificateFileId(), now);
        MilestoneEvidenceFile.requireReady(command.buyerEvidenceFileId(), command.deliveryCertificateFileId(), known);
        ConsentEvidence consent = BuyerReceiptConsent.record(milestone, command.actorId(),
                command.buyerReceivedWeight(), command.buyerEvidenceFileId(), command.deliveryCertificateFileId(),
                command.statementVersion(), command.presentedContentHash(), statements, UUID.randomUUID(), now,
                command.idempotencyKey().toString());

        consents.append(consent);
        milestones.update(milestone);
        evidence.markNamed(fileIds, now);
        events.milestoneBuyerReceived(contract, milestone);
        if (MilestoneCleanSettlement.requiresInspection(contract)) {
            // D77: the report is required before settlement, so Level 1.5 is asked for as soon as the goods are received.
            level15.request(contract, milestone, InspectionRequestReason.MANDATORY_INSPECTION, null, now);
        }
        idempotencyStore.recordResult(command.actorId(), OPERATION, command.idempotencyKey(),
                consent.consentId().toString());
        log.info("event=milestone_buyer_received contractId={} milestoneId={} weightKg={}",
                command.contractId(), milestone.milestoneId(), SellerWeight.plain(command.buyerReceivedWeight()));
        return fileIds;
    }

    /**
     * The 403 and 404 of receive, checked before the body is read (API order: party, milestone, request, state). The
     * transaction checks them again under the locks.
     */
    public void requireBuyerOfMilestone(String contractId, String milestoneId, String actorId) {
        transactions.inTransaction(() -> {
            Contract contract = contracts.findById(new ContractId(contractId))
                    .orElseThrow(() -> new ContractNotFoundException(contractId));
            if (contract.roleOf(actorId).filter(role -> role == SignerRole.BUYER).isEmpty()) {
                throw new UnauthorizedContractAccessException(
                        "Only the buyer of this contract can record the receipt.");
            }
            milestones.findById(milestoneId)
                    .filter(m -> m.contractId().equals(contractId))
                    .orElseThrow(() -> new MilestoneNotFoundException(contractId, milestoneId));
        });
    }

    /** The weight in its canonical three-decimal form, so 9500 and 9500.000 are the same request. */
    private static String requestHash(MilestoneEvidenceCommands.Receive command) {
        return CanonicalTerms.sha256(String.join("\n", command.contractId(), command.milestoneId(),
                SellerWeight.kg(command.buyerReceivedWeight()), command.buyerEvidenceFileId(),
                String.valueOf(command.deliveryCertificateFileId()), command.statementVersion(),
                command.presentedContentHash()));
    }
}
