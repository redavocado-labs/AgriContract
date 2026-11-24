package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.model.remedy.RemedyFundType;

import java.util.Optional;

/** RemedyDecision and its legs (V23): inserted once; only the status moves, PENDING_CONTEST to FINALIZED. */
public interface RemedyDecisionRepository {

    /** Inserts the decision and its legs. At most one decision per breach case. */
    void insert(RemedyDecision decision);

    /** Plain read, no lock. */
    Optional<RemedyDecision> findById(String remedyDecisionId);

    /** Reads under the row lock; the caller already holds the contract lock. */
    Optional<RemedyDecision> findByIdForUpdate(String remedyDecisionId);

    /** The latest decision of the case (round 2 once a contest superseded round 1). */
    Optional<RemedyDecision> findByBreachCaseId(String breachCaseId);

    /** PENDING_CONTEST becomes FINALIZED with finalizedAt; nothing else of the decision is written. */
    void markFinalized(RemedyDecision finalized);

    /** PENDING_CONTEST becomes SUPERSEDED at {@code at} (a contest's second Level 1.5 result replaced it); no event. */
    void markSuperseded(String remedyDecisionId, java.time.Instant at);

    /**
     * The DEPOSIT_FORFEITURE legs taken so far from that deposit by the contract's remedy decisions, FINALIZED or
     * PENDING_CONTEST, never SUPERSEDED: what the deposit has already lost (0.00 when none). A deposit return leg of
     * the normal completion (#238) is not a forfeiture and never counts.
     */
    VndAmount depositForfeited(String contractId, RemedyFundType deposit);
}
