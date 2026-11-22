package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.util.Optional;

/** RemedyDecision and its legs (V23): inserted once; only the status moves, PENDING_CONTEST to FINALIZED. */
public interface RemedyDecisionRepository {

    /** Inserts the decision and its legs. At most one decision per breach case. */
    void insert(RemedyDecision decision);

    /** Plain read, no lock. */
    Optional<RemedyDecision> findById(String remedyDecisionId);

    /** Reads under the row lock; the caller already holds the contract lock. */
    Optional<RemedyDecision> findByIdForUpdate(String remedyDecisionId);

    Optional<RemedyDecision> findByBreachCaseId(String breachCaseId);

    /** PENDING_CONTEST becomes FINALIZED with finalizedAt; nothing else of the decision is written. */
    void markFinalized(RemedyDecision finalized);

    /**
     * The SELLER_DEPOSIT legs taken so far by the contract's remedy decisions, FINALIZED or PENDING_CONTEST: what the
     * seller deposit has already lost (0.00 when none).
     */
    VndAmount sellerDepositForfeited(String contractId);
}
