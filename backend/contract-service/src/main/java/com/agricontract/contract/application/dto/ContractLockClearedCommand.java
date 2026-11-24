package com.agricontract.contract.application.dto;

import java.util.List;

/**
 * escrow.contract_lock_cleared (D93), parsed: every remedy decision of the contract whose legs all completed, with the
 * contract lock at 0.00. eventId is the causation of contract.settled.
 */
public record ContractLockClearedCommand(String contractId, List<String> remedyDecisionIds, String eventId,
                                         String correlationId) {
    public ContractLockClearedCommand {
        remedyDecisionIds = List.copyOf(remedyDecisionIds);
    }
}
