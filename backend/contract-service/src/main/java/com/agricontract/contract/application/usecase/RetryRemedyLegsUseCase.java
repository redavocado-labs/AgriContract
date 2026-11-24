package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.RetryRemedyLegsCommand;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.IdempotencyKeyReusedException;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.IdempotencyStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * OPERATOR RetryRemedyLegs (golden-flow-api retryRemedyLegs, D93): once the cause is fixed, ask escrow to resend every
 * FAILED remedy leg of the contract (contract.remedy_retry_requested). Only for an ACTIVE contract. The leg states are
 * escrow's: with no FAILED leg escrow does nothing. A replay of the same Idempotency-Key does nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryRemedyLegsUseCase {
    static final String OPERATION = "RETRY_REMEDY_LEGS";

    private final IdempotencyStore idempotencyStore;
    private final ContractRepository contracts;
    private final ContractEventPublisher events;
    private final Clock clock;
    private final TransactionRunner transactions;

    public void execute(RetryRemedyLegsCommand command) {
        transactions.inTransaction(() -> retry(command));
    }

    private void retry(RetryRemedyLegsCommand command) {
        IdempotencyStore.Outcome outcome = idempotencyStore.claim(command.operatorUserId(), OPERATION,
                command.idempotencyKey(), CanonicalTerms.sha256(command.contractId()));
        if (outcome == IdempotencyStore.Outcome.CONFLICT) {
            throw new IdempotencyKeyReusedException();
        }
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(command.contractId()));
        if (outcome == IdempotencyStore.Outcome.REPLAY) {
            log.info("event=remedy_retry_replayed contractId={} key={}", command.contractId(),
                    command.idempotencyKey());
            return;
        }
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new ContractConflictException(ContractConflictException.REMEDY_RETRY_NOT_ALLOWED,
                    "Remedy legs can only be retried on an ACTIVE contract, not " + contract.getStatus() + ".");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        events.remedyRetryRequested(contract, command.operatorUserId(), now);
        log.info("event=remedy_retry_requested contractId={} operator={}", command.contractId(),
                command.operatorUserId());
    }
}
