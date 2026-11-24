package com.agricontract.escrow.domain.repository;

import com.agricontract.escrow.domain.model.RemedyDecision;
import com.agricontract.escrow.domain.model.RemedyLeg;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** remedy_decision and remedy_leg (V14). Writes need the caller's transaction. */
public interface RemedyLegRepository {

    /** The decision row, row-locked until the caller's transaction ends. */
    Optional<RemedyDecision> findDecisionForUpdate(String remedyDecisionId);

    void insertDecision(RemedyDecision decision);

    void insert(RemedyLeg leg);

    List<RemedyLeg> findByDecisionId(String remedyDecisionId);

    /** One leg, row-locked until the caller's transaction ends. */
    Optional<RemedyLeg> findByIdForUpdate(String remedyLegId);

    /** Writes the result fields. */
    void update(RemedyLeg leg);

    /** Refused legs whose resend is due. */
    List<String> findWithRetryDue(Instant now, int limit);

    /** Every leg of the contract, row-locked until the caller's transaction ends, in remedyLegId order. */
    List<RemedyLeg> findByContractIdForUpdate(String contractId);

    /** The ACCEPTED decisions of the contract, sorted; plain read. */
    List<String> findAcceptedDecisionIds(String contractId);

    /** True when a leg of the contract is SENT or FAILED (its money not moved yet); plain read. */
    boolean hasLegInFlight(String contractId);
}
