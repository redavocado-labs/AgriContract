package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.ContractLockClearedCommand;
import com.agricontract.contract.application.exception.TerminalCommitRefusedException;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.TerminalCommitResult;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * escrow.contract_lock_cleared (D93): the contract takes its pending terminal status only when the decision it waits
 * for is among the cleared ones, with contract.settled in the same transaction. Any other outcome (not ACTIVE, nothing
 * pending, the pending decision not cleared yet) changes nothing and is acked. Unknown contract: refused (DLQ).
 * Runs inside the consumer's transaction, after its processed_event claim.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommitTerminalAfterLockClearedUseCase {
    private final ContractRepository contracts;
    private final ContractEventPublisher events;
    private final Clock clock;

    public void execute(ContractLockClearedCommand command) {
        Contract contract = contracts.findByIdForUpdate(new ContractId(command.contractId()))
                .orElseThrow(() -> new TerminalCommitRefusedException("escrow.contract_lock_cleared "
                        + command.eventId() + " for unknown contract " + command.contractId()));
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        TerminalCommitResult result = contract.commitTerminal(command.remedyDecisionIds(), now);
        if (result != TerminalCommitResult.COMMITTED) {
            log.info("event=contract_lock_cleared_ignored contractId={} eventId={} result={} status={}",
                    command.contractId(), command.eventId(), result, contract.getStatus());
            return;
        }
        contracts.save(contract);
        events.contractSettled(contract, now, command.correlationId(), command.eventId());
        log.info("event=contract_settled contractId={} decisionId={} eventId={}", command.contractId(),
                contract.getPendingTerminalDecisionId(), command.eventId());
    }
}
