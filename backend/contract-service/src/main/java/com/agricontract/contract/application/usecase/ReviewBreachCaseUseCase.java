package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.exception.BreachCaseNotFoundException;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * reviewBreachCase (D80): an ARBITRATOR (checked at the endpoint) takes a REPORTED case: UNDER_REVIEW. One transaction
 * under the contract lock then the case lock. Nothing else changes: no money, reputation, milestone or event. A repeat
 * of the same Idempotency-Key is a no-op.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewBreachCaseUseCase {
    static final String OPERATION = "REVIEW_BREACH_CASE";

    private final ContractRepository contracts;
    private final BreachCaseRepository breachCases;
    private final IdempotencyStore idempotencyStore;
    private final TransactionRunner transactions;

    public void execute(BreachCaseCommands.Review command) {
        transactions.inTransaction(() -> review(command));
    }

    private void review(BreachCaseCommands.Review command) {
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        BreachCase breachCase = breachCases.findByIdForUpdate(command.breachCaseId())
                .filter(c -> c.contractId().equals(contract.getContractId().value()))
                .orElseThrow(() -> new BreachCaseNotFoundException(command.contractId(), command.breachCaseId()));
        IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.actorId(), OPERATION,
                command.idempotencyKey(), CanonicalTerms.sha256(command.contractId() + "\n" + command.breachCaseId()));
        if (outcome == IdempotencyStore.Outcome.CONFLICT) {
            throw new IdempotencyKeyReusedException();
        }
        if (outcome == IdempotencyStore.Outcome.REPLAY) {
            return;
        }
        breachCases.update(breachCase.review());
        idempotencyStore.recordResult(command.actorId(), OPERATION, command.idempotencyKey(), command.breachCaseId());
        log.info("event=breach_case_reviewed breachCaseId={} actorId={}", command.breachCaseId(), command.actorId());
    }
}
