package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.MilestoneCleanSettlement;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.consent.ConsentEvidence;
import com.agricontract.contract.domain.model.consent.OperationalConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.CleanReceiptConsent;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.milestone.SettlementPath;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ConsentEvidenceRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * confirmMilestoneClean (D77). One transaction under the contract and milestone locks: party (403), milestone of the
 * contract (404), the Idempotency-Key (a repeat is a no-op 204), then the contract and milestone state, the inspection
 * guard and the consent (409). Then the consent is appended and the milestone is settled (MilestoneCleanSettlement).
 * A confirm that arrives after the confirm window, while the milestone is still BUYER_RECEIVED, is accepted: the
 * outcome is the same as the timeout.
 */
@Service
@RequiredArgsConstructor
public class ConfirmMilestoneCleanUseCase {
    static final String OPERATION = "CONFIRM_MILESTONE_CLEAN";

    private final ContractRepository contracts;
    private final MilestoneRepository milestones;
    private final ConsentEvidenceRepository consents;
    private final OperationalConsentStatements statements;
    private final IdempotencyStore idempotencyStore;
    private final MilestoneCleanSettlement settlement;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void execute(MilestoneEvidenceCommands.Confirm command) {
        transactions.inTransaction(() -> confirm(command));
    }

    private void confirm(MilestoneEvidenceCommands.Confirm command) {
        // Contract lock first, as every contract-service write does, then the milestone.
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        if (contract.roleOf(command.actorId()).filter(role -> role == SignerRole.BUYER).isEmpty()) {
            throw new UnauthorizedContractAccessException("Only the buyer of this contract can confirm the receipt.");
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
            return;
        }

        // 409: contract and milestone state, the inspection guard, then the consent. A refusal rolls everything back.
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The contract is " + contract.getStatus() + "; the receipt can only be confirmed while it is ACTIVE.");
        }
        if (milestone.status() != MilestoneStatus.BUYER_RECEIVED) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The receipt can only be confirmed on a BUYER_RECEIVED milestone (" + milestone.status() + ").");
        }
        settlement.requireNoPendingInspection(contract, milestone);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        ConsentEvidence consent = CleanReceiptConsent.record(milestone, command.actorId(), command.statementVersion(),
                command.presentedContentHash(), statements, UUID.randomUUID(), now, command.idempotencyKey().toString());

        consents.append(consent);
        settlement.settle(contract, milestone, SettlementPath.CLEAN_CONFIRMED, now);
        idempotencyStore.recordResult(command.actorId(), OPERATION, command.idempotencyKey(),
                consent.consentId().toString());
    }

    /**
     * The 403 and 404 of confirm, checked before the body is read (API order: party, milestone, request, state). The
     * transaction checks them again under the locks.
     */
    public void requireBuyerOfMilestone(String contractId, String milestoneId, String actorId) {
        transactions.inTransaction(() -> {
            Contract contract = contracts.findById(new ContractId(contractId))
                    .orElseThrow(() -> new ContractNotFoundException(contractId));
            if (contract.roleOf(actorId).filter(role -> role == SignerRole.BUYER).isEmpty()) {
                throw new UnauthorizedContractAccessException(
                        "Only the buyer of this contract can confirm the receipt.");
            }
            milestones.findById(milestoneId)
                    .filter(m -> m.contractId().equals(contractId))
                    .orElseThrow(() -> new MilestoneNotFoundException(contractId, milestoneId));
        });
    }

    private static String requestHash(MilestoneEvidenceCommands.Confirm command) {
        return CanonicalTerms.sha256(String.join("\n", command.contractId(), command.milestoneId(),
                command.statementVersion(), command.presentedContentHash()));
    }
}
