package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.usecase.CheckContractClearanceUseCase;
import com.agricontract.escrow.domain.model.ContractClearanceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the contracts whose remedy or settlement leg completed (#609, D93). Per contract: the reads and the bank
 * ledger call run outside any transaction, the conclusion in one transaction of its own, so one contract that fails
 * does not hold back the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractClearanceScheduler {

    private final CheckContractClearanceUseCase clearance;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${escrow.clearance.check-interval}")
    public void checkRequestedContracts() {
        for (ContractClearanceRequest request : clearance.requested()) {
            try {
                clearance.read(request).ifPresent(reading ->
                        transactionTemplate.executeWithoutResult(status -> clearance.conclude(reading)));
            } catch (RuntimeException e) {
                log.error("event=contract_clearance_error contractId={}: {}", request.contractId(), e.getMessage(), e);
            }
        }
    }
}
