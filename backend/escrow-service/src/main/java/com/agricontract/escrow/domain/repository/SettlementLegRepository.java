package com.agricontract.escrow.domain.repository;

import com.agricontract.escrow.domain.model.SettlementLeg;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SettlementLegRepository {

    void insert(SettlementLeg leg);

    /**
     * The settlement legs of one milestone, row-locked until the caller's transaction ends. Callers lock the
     * escrow_milestone row first, so results and retries of one milestone never deadlock.
     */
    List<SettlementLeg> findByMilestoneIdForUpdate(String milestoneId);

    /** The milestone of this leg, read without a lock (the caller locks the milestone next). */
    Optional<String> findMilestoneIdByLegId(String legId);

    /** Writes the result fields (status, ledger entry, failures, next attempt, reason, updatedAt). */
    void update(SettlementLeg leg);

    /** Refused legs whose resend is due. */
    List<String> findWithRetryDue(Instant now, int limit);
}
