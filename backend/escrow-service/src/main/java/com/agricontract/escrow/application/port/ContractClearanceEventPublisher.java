package com.agricontract.escrow.application.port;

import java.time.Instant;
import java.util.List;

// Writes escrow.contract_lock_cleared to the outbox (#609, D93). Must run inside the caller's transaction.
public interface ContractClearanceEventPublisher {

    /** remedyDecisionIds: every ACCEPTED remedy decision of the contract, sorted; all of their legs are COMPLETED. */
    void contractLockCleared(String contractId, List<String> remedyDecisionIds, Instant clearedAt);
}
