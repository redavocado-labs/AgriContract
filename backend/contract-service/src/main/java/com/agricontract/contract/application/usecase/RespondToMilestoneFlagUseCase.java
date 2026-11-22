package com.agricontract.contract.application.usecase;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.MilestoneFlagSettings;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.MilestoneContestStep;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.consent.ConsentEvidence;
import com.agricontract.contract.domain.model.consent.OperationalConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.ContestFlagConsent;
import com.agricontract.contract.domain.model.milestone.ContestedBy;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
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
 * respondToMilestoneFlag (D80): the seller contests a flag inside sellerResponseWindowDays business days of
 * flaggedAt. Party (403), milestone of the contract (404), the Idempotency-Key (a repeat is a no-op 204), then the
 * milestone state, the response window and the consent (409), then the contest step (MilestoneContestStep): CONTESTED,
 * a BreachCase allegation, breach.reported and inspection.level1_5_requested. No money or reputation consequence.
 */
@Service
@RequiredArgsConstructor
public class RespondToMilestoneFlagUseCase {
    static final String OPERATION = "RESPOND_TO_MILESTONE_FLAG";

    private final ContractRepository contracts;
    private final MilestoneRepository milestones;
    private final ConsentEvidenceRepository consents;
    private final OperationalConsentStatements statements;
    private final IdempotencyStore idempotencyStore;
    private final MilestoneContestStep contestStep;
    private final MilestoneFlagSettings settings;
    private final VietnamBusinessDays calendar;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void execute(MilestoneEvidenceCommands.Respond command) {
        transactions.inTransaction(() -> respond(command));
    }

    private void respond(MilestoneEvidenceCommands.Respond command) {
        // Contract lock first, as every contract-service write does, then the milestone.
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        if (contract.roleOf(command.actorId()).filter(role -> role == SignerRole.SELLER).isEmpty()) {
            throw new UnauthorizedContractAccessException("Only the seller of this contract can respond to a flag.");
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

        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The contract is " + contract.getStatus() + "; a flag can only be answered while it is ACTIVE.");
        }
        if (milestone.status() != MilestoneStatus.AWAITING_SELLER_RESPONSE || milestone.flag() == null) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "Only an AWAITING_SELLER_RESPONSE milestone can be contested (" + milestone.status() + ").");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Instant deadline = calendar.deadline(milestone.flag().flaggedAt(), settings.sellerResponseWindowDays());
        if (!now.isBefore(deadline)) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The response window closed at " + deadline + ".");
        }
        ConsentEvidence consent = ContestFlagConsent.record(milestone, command.actorId(), command.statementVersion(),
                command.presentedContentHash(), statements, UUID.randomUUID(), now,
                command.idempotencyKey().toString());

        consents.append(consent);
        contestStep.contest(contract, milestone, ContestedBy.SELLER, command.actorId(), now);
        idempotencyStore.recordResult(command.actorId(), OPERATION, command.idempotencyKey(),
                consent.consentId().toString());
    }

    /** The 403 and 404 of respond, checked before the body is read (API order: party, milestone, request, state). */
    public void requireSellerOfMilestone(String contractId, String milestoneId, String actorId) {
        transactions.inTransaction(() -> {
            Contract contract = contracts.findById(new ContractId(contractId))
                    .orElseThrow(() -> new ContractNotFoundException(contractId));
            if (contract.roleOf(actorId).filter(role -> role == SignerRole.SELLER).isEmpty()) {
                throw new UnauthorizedContractAccessException(
                        "Only the seller of this contract can respond to a flag.");
            }
            milestones.findById(milestoneId)
                    .filter(m -> m.contractId().equals(contractId))
                    .orElseThrow(() -> new MilestoneNotFoundException(contractId, milestoneId));
        });
    }

    private static String requestHash(MilestoneEvidenceCommands.Respond command) {
        return CanonicalTerms.sha256(String.join("\n", command.contractId(), command.milestoneId(),
                command.statementVersion(), command.presentedContentHash()));
    }
}
