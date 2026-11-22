package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.MilestoneDeliverySettings;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.EvidenceAttachment;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.consent.ConsentEvidence;
import com.agricontract.contract.domain.model.consent.OperationalConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.model.milestone.SellerWeighConsent;
import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.model.milestone.SellerWeight;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ConsentEvidenceRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.domain.repository.SellerWeighDeclarationRepository;
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
 * recordSellerWeighed (D71-D75, design §3.2.1). One transaction under the contract and milestone locks: party (403),
 * milestone of the contract (404), the Idempotency-Key (a repeat is a no-op 204), the request and its files (400),
 * the contract and milestone state, the files' scan and the consent (409). Then the declaration is appended with its
 * consent, the milestone holds it as the one in force, the files are marked named and milestone.seller_weighed is
 * written to the outbox. After the commit the named files are confirmed to file-service (best effort; a job retries).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordSellerWeighedUseCase {
    static final String OPERATION = "RECORD_SELLER_WEIGHED";

    private final ContractRepository contracts;
    private final MilestoneRepository milestones;
    private final MilestoneEvidenceRepository evidence;
    private final SellerWeighDeclarationRepository declarations;
    private final ConsentEvidenceRepository consents;
    private final OperationalConsentStatements statements;
    private final IdempotencyStore idempotencyStore;
    private final ContractEventPublisher events;
    private final EvidenceAttachment attachment;
    private final MilestoneDeliverySettings settings;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void execute(MilestoneEvidenceCommands.Weigh command) {
        List<String> named = transactions.inTransaction(() -> declare(command));
        attachment.confirmNow(named);
    }

    private List<String> declare(MilestoneEvidenceCommands.Weigh command) {
        // Contract lock first, as every contract-service write does, then the milestone.
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        if (contract.roleOf(command.actorId()).filter(role -> role == SignerRole.SELLER).isEmpty()) {
            throw new UnauthorizedContractAccessException("Only the seller of this contract can declare the weight.");
        }
        Milestone milestone = milestones.findByIdForUpdate(command.milestoneId())
                .filter(m -> m.contractId().equals(command.contractId()))
                .orElseThrow(() -> new MilestoneNotFoundException(command.contractId(), command.milestoneId()));
        IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.actorId(), OPERATION,
                command.idempotencyKey(), requestHash(command));
        if (outcome == IdempotencyStore.Outcome.CONFLICT) {
            throw new IdempotencyKeyReusedException();
        }
        if (outcome == IdempotencyStore.Outcome.REPLAY) {
            return List.of();
        }

        // 400: the request itself.
        SellerWeight.requireValid(command.sellerDeclaredWeight());
        List<String> fileIds = new ArrayList<>();
        fileIds.add(command.sellerEvidenceFileId());
        if (command.deliveryCertificateFileId() != null) {
            fileIds.add(command.deliveryCertificateFileId());
        }
        Map<String, MilestoneEvidenceFile> known = evidence.findByIds(fileIds);
        MilestoneEvidenceFile.requireUploadedBy(command.sellerEvidenceFileId(), command.deliveryCertificateFileId(),
                command.actorId(), milestone.milestoneId(), known);

        // 409: contract and milestone state, then the files' scan, then the consent. A refusal rolls everything back,
        // the milestone changed in memory included.
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The contract is " + contract.getStatus() + "; the weight can only be declared while it is ACTIVE.");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int number = milestone.declareSellerWeight(command.sellerDeclaredWeight(), command.sellerEvidenceFileId(),
                command.deliveryCertificateFileId(), now, settings.weighCorrectionWindow());
        MilestoneEvidenceFile.requireReady(command.sellerEvidenceFileId(), command.deliveryCertificateFileId(), known);
        ConsentEvidence consent = SellerWeighConsent.record(milestone, command.actorId(),
                command.sellerDeclaredWeight(), command.sellerEvidenceFileId(), command.deliveryCertificateFileId(),
                command.statementVersion(), command.presentedContentHash(), statements, UUID.randomUUID(), now,
                command.idempotencyKey().toString());

        consents.append(consent);
        SellerWeighDeclaration declaration = new SellerWeighDeclaration(UUID.randomUUID(), milestone.milestoneId(),
                command.contractId(), number, command.sellerDeclaredWeight(), command.sellerEvidenceFileId(),
                command.deliveryCertificateFileId(), consent.consentId(), command.actorId(), now);
        declarations.append(declaration);
        milestones.update(milestone);
        evidence.markNamed(fileIds, now);
        events.milestoneSellerWeighed(contract, milestone, declaration);
        idempotencyStore.recordResult(command.actorId(), OPERATION, command.idempotencyKey(),
                declaration.declarationId().toString());
        log.info("event=milestone_seller_weighed contractId={} milestoneId={} declarationNumber={} weightKg={}",
                command.contractId(), milestone.milestoneId(), number,
                SellerWeight.plain(command.sellerDeclaredWeight()));
        return fileIds;
    }

    private static String requestHash(MilestoneEvidenceCommands.Weigh command) {
        return CanonicalTerms.sha256(String.join("\n", command.contractId(), command.milestoneId(),
                command.sellerDeclaredWeight() == null ? "" : command.sellerDeclaredWeight().toPlainString(),
                command.sellerEvidenceFileId(), String.valueOf(command.deliveryCertificateFileId()),
                command.statementVersion(), command.presentedContentHash()));
    }
}
