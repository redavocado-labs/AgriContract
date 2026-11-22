package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.exception.BreachCaseNotFoundException;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * resolveBreachCase (D81): an ARBITRATOR (checked at the endpoint) records the AttributionDecision on a case
 * UNDER_REVIEW. Check order: party and case (404), the body (400, at the endpoint), then the state and the D81 guards
 * (409, in BreachCase.resolve). One transaction under the contract lock then the case lock. No event, no money and no
 * milestone change: remedy.finalized, settlement and the contest window are #137 and #241.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolveBreachCaseUseCase {
    static final String OPERATION = "RESOLVE_BREACH_CASE";

    private final ContractRepository contracts;
    private final BreachCaseRepository breachCases;
    private final MilestoneRepository milestones;
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
                command.evidenceRefs(), contested, now, command.actorId());
        breachCases.update(resolved);
        idempotencyStore.recordResult(command.actorId(), OPERATION, command.idempotencyKey(), command.breachCaseId());
        log.info("event=breach_case_resolved breachCaseId={} finalBreachingRole={} decisionSource={}",
                command.breachCaseId(), resolved.finalBreachingRole(), resolved.decisionSource());
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
