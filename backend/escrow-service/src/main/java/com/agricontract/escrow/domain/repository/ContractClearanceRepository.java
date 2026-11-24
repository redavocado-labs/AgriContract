package com.agricontract.escrow.domain.repository;

import com.agricontract.escrow.domain.model.ContractClearanceRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** contract_clearance_check (V15). */
public interface ContractClearanceRepository {

    /** Asks for a (new) check of the contract: requested_at = now, checked_at = NULL. Needs the caller's transaction. */
    void request(String contractId, Instant now);

    /** Rows not checked since their last request, oldest request first; plain read. */
    List<ContractClearanceRequest> findUnchecked(int limit);

    /** The row, locked until the caller's transaction ends. */
    Optional<ContractClearanceRequest> findForUpdate(String contractId);

    /**
     * Marks the row checked only if it was not requested again since requestedAt: a completion committed meanwhile
     * keeps it unchecked. One statement, with or without the caller's transaction. True when the row was marked.
     */
    boolean markChecked(String contractId, Instant requestedAt, Instant now);
}
