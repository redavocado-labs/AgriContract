package com.agricontract.contract.application.usecase;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.consent.ConsentEvidence;
import com.agricontract.contract.domain.model.consent.OperationalConsentStatements;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.FlagMilestoneConsent;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ConsentEvidenceRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.application.port.MilestoneStatusMail;
import com.agricontract.contract.application.port.MilestoneFlagSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * flagMilestoneIssue (D80, D82). One transaction under the contract and milestone locks: party (403), milestone of the
 * contract (404), the Idempotency-Key (a repeat is a no-op 204), then the contract and milestone state, the confirm
 * window and the consent (409). The milestone becomes AWAITING_SELLER_RESPONSE and the seller's mail is requested (no milestone.flagged, D101). A
 * flag is an allegation: no money or reputation moves, and a mandatory inspection does not block it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlagMilestoneUseCase {
    static final String OPERATION = "FLAG_MILESTONE_ISSUE";

    private final ContractRepository contracts;
    private final MilestoneRepository milestones;
    private final ConsentEvidenceRepository consents;
    private final OperationalConsentStatements statements;
    private final IdempotencyStore idempotencyStore;
    private final ContractEventPublisher events;
    private final VietnamBusinessDays calendar;
    private final MilestoneFlagSettings flagSettings;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void execute(MilestoneEvidenceCommands.Flag command) {
        transactions.inTransaction(() -> flag(command));
    }

    private void flag(MilestoneEvidenceCommands.Flag command) {
        // Contract lock first, as every contract-service write does, then the milestone.
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        if (contract.roleOf(command.actorId()).filter(role -> role == SignerRole.BUYER).isEmpty()) {
            throw new UnauthorizedContractAccessException("Only the buyer of this contract can flag the milestone.");
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

        // 409: contract and milestone state, the confirm window, then the consent. A refusal rolls everything back.
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The contract is " + contract.getStatus() + "; a milestone can only be flagged while it is ACTIVE.");
        }
        if (milestone.status() != MilestoneStatus.BUYER_RECEIVED) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "Only a BUYER_RECEIVED milestone can be flagged (" + milestone.status() + ").");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int days = contract.getCoffeeTerms().snapshot().negotiated().buyerConfirmWindowDays();
        Instant deadline = calendar.deadline(milestone.buyerReceipt().receivedAt(), days);
        if (!now.isBefore(deadline)) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The confirm window closed at " + deadline + "; the milestone can no longer be flagged.");
        }
        ConsentEvidence consent = FlagMilestoneConsent.record(milestone, command.issueType(), command.actorId(),
                command.statementVersion(), command.presentedContentHash(), statements, UUID.randomUUID(), now,
                command.idempotencyKey().toString());

        consents.append(consent);
        milestone.flagIssue(command.issueType(), now);
        milestones.update(milestone);
        events.milestoneStatusRequested(contract, milestone, MilestoneStatusMail.FLAGGED,
                calendar.deadline(now, flagSettings.sellerResponseWindowDays()), now);
        idempotencyStore.recordResult(command.actorId(), OPERATION, command.idempotencyKey(),
                consent.consentId().toString());
        log.info("event=milestone_flagged contractId={} milestoneId={} issueType={}", command.contractId(),
                command.milestoneId(), command.issueType());
    }

    /** The 403 and 404 of flag, checked before the body is read (API order: party, milestone, request, state). */
    public void requireBuyerOfMilestone(String contractId, String milestoneId, String actorId) {
        transactions.inTransaction(() -> {
            Contract contract = contracts.findById(new ContractId(contractId))
                    .orElseThrow(() -> new ContractNotFoundException(contractId));
            if (contract.roleOf(actorId).filter(role -> role == SignerRole.BUYER).isEmpty()) {
                throw new UnauthorizedContractAccessException("Only the buyer of this contract can flag the milestone.");
            }
            milestones.findById(milestoneId)
                    .filter(m -> m.contractId().equals(contractId))
                    .orElseThrow(() -> new MilestoneNotFoundException(contractId, milestoneId));
        });
    }

    private static String requestHash(MilestoneEvidenceCommands.Flag command) {
        return CanonicalTerms.sha256(String.join("\n", command.contractId(), command.milestoneId(),
                command.issueType().name(), command.statementVersion(), command.presentedContentHash()));
    }
}
