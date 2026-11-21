package com.agricontract.escrow.domain.repository;

import com.agricontract.escrow.domain.model.EscrowMilestone;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EscrowMilestoneRepository {

    /** Inserts a new funding. A second one for the same milestoneId violates the primary key. */
    void insert(EscrowMilestone milestone);

    /** Row-locked until the caller's transaction ends: serialises the request, results and retries of one milestone. */
    Optional<EscrowMilestone> findByIdForUpdate(String milestoneId);

    /** Writes the result fields (status, ledger entry, lockedAt, failures, next attempt, reason, updatedAt). */
    void update(EscrowMilestone milestone);

    /** Milestones whose failed funding request is due to be resent. */
    List<String> findWithRetryDue(Instant now, int limit);
}
