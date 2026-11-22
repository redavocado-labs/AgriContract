package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.exception.BreachCaseNotFoundException;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.EvidenceAttachment;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.InspectionReportRepository;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * contestBreachDecision and contestBreachDecisionEvidence (GB-50, D85, D91). The party whose role is the decision's
 * finalBreachingRole contests once, before contestDeadline: the case becomes CONTESTED, the decision stays
 * PENDING_CONTEST and inspection.level1_5_requested (DECISION_CONTESTED) asks for the second Level 1.5 report. No
 * consent: a contest only asks for a re-inspection. API order: role (endpoint), case (404), party (403), the body (400,
 * files uploaded by the caller), then the state (409, files READY). One transaction under the contract lock, then the
 * case, the milestone and the decision.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContestBreachDecisionUseCase {
    static final String CONTEST = "CONTEST_BREACH_DECISION";
    static final String EVIDENCE = "CONTEST_BREACH_DECISION_EVIDENCE";

    private final ContractRepository contracts;
    private final BreachCaseRepository breachCases;
    private final MilestoneRepository milestones;
    private final RemedyDecisionRepository remedyDecisions;
    private final InspectionReportRepository reports;
    private final MilestoneEvidenceRepository evidence;
    private final ContractEventPublisher events;
    private final EvidenceAttachment attachment;
    private final IdempotencyStore idempotencyStore;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void contest(BreachCaseCommands.Contest command) {
        List<String> named = transactions.inTransaction(() -> doContest(command));
        attachment.confirmNow(named);
    }

    public void addEvidence(BreachCaseCommands.AddContestEvidence command) {
        List<String> named = transactions.inTransaction(() -> doAddEvidence(command));
        attachment.confirmNow(named);
    }

    private List<String> doContest(BreachCaseCommands.Contest command) {
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        BreachCase breachCase = breachCases.findByIdForUpdate(command.breachCaseId())
                .filter(c -> c.contractId().equals(command.contractId()))
                .orElseThrow(() -> new BreachCaseNotFoundException(command.contractId(), command.breachCaseId()));
        SignerRole role = partyOf(contract, command.actorId());
        breachCase.requireContester(role);
        IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.actorId(), CONTEST,
                command.idempotencyKey(), contestHash(command));
        if (outcome == IdempotencyStore.Outcome.CONFLICT) {
            throw new IdempotencyKeyReusedException();
        }
        if (outcome == IdempotencyStore.Outcome.REPLAY) {
            return List.of();
        }

        // 400: the files are the caller's own uploads for this milestone. 409: the state, then the scan.
        Map<String, MilestoneEvidenceFile> known = evidence.findByIds(command.evidenceFileIds());
        MilestoneEvidenceFile.requireAllUploadedBy(command.evidenceFileIds(), command.actorId(),
                breachCase.milestoneId(), known);
        Instant now = now();
        BreachCase contested = breachCase.contest(role, command.reason(), command.evidenceFileIds(), now,
                command.actorId());
        MilestoneEvidenceFile.requireAllReady(command.evidenceFileIds(), known);

        Milestone milestone = milestones.findByIdForUpdate(breachCase.milestoneId()).orElseThrow(
                () -> new IllegalStateException("Milestone " + breachCase.milestoneId() + " not found"));
        requirePending(breachCase);
        breachCases.update(contested);
        evidence.markNamed(command.evidenceFileIds(), now);
        events.inspectionLevel15Requested(contract, milestone, InspectionRequestReason.DECISION_CONTESTED,
                command.actorId(), now);
        idempotencyStore.recordResult(command.actorId(), CONTEST, command.idempotencyKey(), command.breachCaseId());
        log.info("event=breach_decision_contested breachCaseId={} contractId={} milestoneId={} role={} files={}",
                command.breachCaseId(), command.contractId(), milestone.milestoneId(), role,
                command.evidenceFileIds().size());
        return command.evidenceFileIds();
    }

    private List<String> doAddEvidence(BreachCaseCommands.AddContestEvidence command) {
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        BreachCase breachCase = breachCases.findByIdForUpdate(command.breachCaseId())
                .filter(c -> c.contractId().equals(command.contractId()))
                .orElseThrow(() -> new BreachCaseNotFoundException(command.contractId(), command.breachCaseId()));
        partyOf(contract, command.actorId());
        breachCase.requireContestOwner(command.actorId());
        IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.actorId(), EVIDENCE,
                command.idempotencyKey(), evidenceHash(command));
        if (outcome == IdempotencyStore.Outcome.CONFLICT) {
            throw new IdempotencyKeyReusedException();
        }
        if (outcome == IdempotencyStore.Outcome.REPLAY) {
            return List.of();
        }

        Map<String, MilestoneEvidenceFile> known = evidence.findByIds(command.fileIds());
        MilestoneEvidenceFile.requireAllUploadedBy(command.fileIds(), command.actorId(), breachCase.milestoneId(),
                known);
        BreachCase updated = breachCase.addContestEvidence(command.actorId(), command.fileIds());
        // D91: only until the second report is confirmed; the milestone is locked after the case, as everywhere.
        milestones.findByIdForUpdate(breachCase.milestoneId());
        if (reports.countByMilestoneId(breachCase.milestoneId()) >= 2) {
            throw new ContractConflictException(ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED,
                    "The second inspection report is already confirmed; evidence can no longer be added.");
        }
        MilestoneEvidenceFile.requireAllReady(command.fileIds(), known);
        List<String> added = command.fileIds().stream().filter(id -> !breachCase.contestEvidenceFileIds().contains(id))
                .toList();
        if (!added.isEmpty()) {
            breachCases.update(updated);
            evidence.markNamed(added, now());
            log.info("event=breach_contest_evidence_added breachCaseId={} added={}", command.breachCaseId(),
                    added.size());
        }
        idempotencyStore.recordResult(command.actorId(), EVIDENCE, command.idempotencyKey(), command.breachCaseId());
        return added;
    }

    /** The 403 and 404 of contest, checked before the body is read (API order: case, party, request, state). */
    public void requireContester(String contractId, String breachCaseId, String actorId) {
        transactions.inTransaction(() -> {
            Contract contract = contracts.findById(new ContractId(contractId))
                    .orElseThrow(() -> new ContractNotFoundException(contractId));
            BreachCase breachCase = breachCases.findById(breachCaseId).filter(c -> c.contractId().equals(contractId))
                    .orElseThrow(() -> new BreachCaseNotFoundException(contractId, breachCaseId));
            breachCase.requireContester(partyOf(contract, actorId));
        });
    }

    /** The 403 and 404 of the evidence top-up, checked before the body is read. */
    public void requireContestOwner(String contractId, String breachCaseId, String actorId) {
        transactions.inTransaction(() -> {
            Contract contract = contracts.findById(new ContractId(contractId))
                    .orElseThrow(() -> new ContractNotFoundException(contractId));
            BreachCase breachCase = breachCases.findById(breachCaseId).filter(c -> c.contractId().equals(contractId))
                    .orElseThrow(() -> new BreachCaseNotFoundException(contractId, breachCaseId));
            partyOf(contract, actorId);
            breachCase.requireContestOwner(actorId);
        });
    }

    /** The decision a contest holds back is still PENDING_CONTEST; anything else is a broken invariant, not a 409. */
    private void requirePending(BreachCase breachCase) {
        RemedyDecision decision = remedyDecisions.findByBreachCaseId(breachCase.breachCaseId())
                .flatMap(d -> remedyDecisions.findByIdForUpdate(d.remedyDecisionId())).orElseThrow(
                        () -> new IllegalStateException("Breach case " + breachCase.breachCaseId()
                                + " has an open contest window but no remedy decision"));
        if (decision.status() != RemedyStatus.PENDING_CONTEST) {
            throw new IllegalStateException("Remedy decision " + decision.remedyDecisionId() + " is "
                    + decision.status() + ", an open contest window needs it PENDING_CONTEST");
        }
    }

    private static SignerRole partyOf(Contract contract, String actorId) {
        return contract.roleOf(actorId).orElseThrow(() ->
                new UnauthorizedContractAccessException("This user is not a party of this contract."));
    }

    private static String contestHash(BreachCaseCommands.Contest c) {
        return CanonicalTerms.sha256(String.join("\n", c.contractId(), c.breachCaseId(), c.reason(),
                String.join("\u0001", c.evidenceFileIds())));
    }

    private static String evidenceHash(BreachCaseCommands.AddContestEvidence c) {
        return CanonicalTerms.sha256(String.join("\n", c.contractId(), c.breachCaseId(),
                String.join("\u0001", c.fileIds())));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
