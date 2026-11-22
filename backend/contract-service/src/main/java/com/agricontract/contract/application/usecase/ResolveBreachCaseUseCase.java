package com.agricontract.contract.application.usecase;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.exception.BreachCaseNotFoundException;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.RemedySettlement;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.remedy.RemedyCalculator;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * resolveBreachCase (D81): an ARBITRATOR (checked at the endpoint) records the AttributionDecision on a case
 * UNDER_REVIEW. Check order: party and case (404), the body (400, at the endpoint), then the state and the D81 guards
 * (409, in BreachCase.resolve). One transaction under the contract lock, then the case lock, then the milestone lock.
 *
 * <p>#137: a case with a computed resolution (the BF-04 path) also gets its RemedyDecision in the same transaction.
 * No fault: the decision is FINALIZED at once, the milestone is SETTLED and remedy.finalized and
 * milestone.dispute_resolved are written. Fault: the decision waits as PENDING_CONTEST, with no event and the
 * milestone still CONTESTED, and the contest window opens (GB-50: 2 business days from the decision, contestStatus
 * OPEN) until the window expires without a contest ({@link ExpireContestWindowsUseCase}) and
 * {@link FinalizeRemedyDecisionUseCase} runs. The decision of a contested case (its second Level 1.5 report, D91) is
 * final at once. A case without a computed resolution gets no RemedyDecision here (system-decided remedies are #240).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolveBreachCaseUseCase {
    static final String OPERATION = "RESOLVE_BREACH_CASE";

    private final ContractRepository contracts;
    private final BreachCaseRepository breachCases;
    private final MilestoneRepository milestones;
    private final RemedyDecisionRepository remedyDecisions;
    private final RemedySettlement remedySettlement;
    private final VietnamBusinessDays calendar;
    private final IdempotencyStore idempotencyStore;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void execute(BreachCaseCommands.Resolve command) {
        transactions.inTransaction(() -> resolve(command));
    }

    private void resolve(BreachCaseCommands.Resolve command) {
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        BreachCase breachCase = breachCases.findByIdForUpdate(command.breachCaseId())
                .filter(c -> c.contractId().equals(contract.getContractId().value()))
                .orElseThrow(() -> new BreachCaseNotFoundException(command.contractId(), command.breachCaseId()));
        IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.actorId(), OPERATION,
                command.idempotencyKey(), requestHash(command));
        if (outcome == IdempotencyStore.Outcome.CONFLICT) {
            throw new IdempotencyKeyReusedException();
        }
        if (outcome == IdempotencyStore.Outcome.REPLAY) {
            return;
        }
        boolean contested = breachCase.milestoneId() != null && milestones.findById(breachCase.milestoneId())
                .map(m -> m.status() == MilestoneStatus.CONTESTED).orElse(false);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        BreachCase resolved = breachCase.resolve(command.finalBreachingRole(), command.decisionSource(),
                command.evidenceRefs(), contested, now, command.actorId(),
                calendar.deadline(now, BreachCase.CONTEST_WINDOW_BUSINESS_DAYS));
        breachCases.update(resolved);
        buildRemedy(contract, resolved, now);
        idempotencyStore.recordResult(command.actorId(), OPERATION, command.idempotencyKey(), command.breachCaseId());
        log.info("event=breach_case_resolved breachCaseId={} finalBreachingRole={} decisionSource={}",
                command.breachCaseId(), resolved.finalBreachingRole(), resolved.decisionSource());
    }

    private void buildRemedy(Contract contract, BreachCase resolved, Instant now) {
        if (resolved.computedResolution() == null || resolved.milestoneId() == null) {
            log.info("event=remedy_not_built reason=no_computed_resolution breachCaseId={}", resolved.breachCaseId());
            return;
        }
        Milestone milestone = milestones.findByIdForUpdate(resolved.milestoneId()).orElseThrow(
                () -> new IllegalStateException("Milestone " + resolved.milestoneId() + " not found"));
        if (milestone.status() != MilestoneStatus.CONTESTED) {
            throw new IllegalStateException("Milestone " + milestone.milestoneId() + " is " + milestone.status()
                    + ", a resolved case needs it CONTESTED");
        }
        var terms = contract.getCoffeeTerms().snapshot();
        BigDecimal left = terms.sellerDepositAmount().value()
                .subtract(remedyDecisions.sellerDepositForfeited(contract.getContractId().value()).value());
        VndAmount remaining = new VndAmount(left.signum() < 0 ? BigDecimal.ZERO : left);
        int settled = (int) milestones.findByContractId(milestone.contractId()).stream()
                .filter(m -> m.status() == MilestoneStatus.SETTLED).count();
        RemedyDecision decision = new RemedyCalculator(() -> UUID.randomUUID().toString())
                .calculate(resolved, milestone, terms, remaining, settled, now);
        remedyDecisions.insert(decision);
        if (decision.status() == RemedyStatus.FINALIZED) {
            remedySettlement.settleAndPublish(contract, decision, milestone, resolved.computedResolution(), now);
        }
        log.info("event=remedy_decision_built remedyDecisionId={} breachCaseId={} status={} legs={}",
                decision.remedyDecisionId(), resolved.breachCaseId(), decision.status(), decision.legs().size());
    }

    /** The 404 of resolve, checked before the body is read (API order: role, case, request, state). */
    public void requireCaseOfContract(String contractId, String breachCaseId) {
        transactions.inTransaction(() -> {
            contracts.findById(new ContractId(contractId)).orElseThrow(() -> new ContractNotFoundException(contractId));
            breachCases.findById(breachCaseId).filter(c -> c.contractId().equals(contractId))
                    .orElseThrow(() -> new BreachCaseNotFoundException(contractId, breachCaseId));
        });
    }

    private static String requestHash(BreachCaseCommands.Resolve c) {
        return CanonicalTerms.sha256(String.join("\n", c.contractId(), c.breachCaseId(),
                String.valueOf(c.finalBreachingRole()), c.decisionSource().name(),
                String.join("\u0001", c.evidenceRefs())));
    }
}
