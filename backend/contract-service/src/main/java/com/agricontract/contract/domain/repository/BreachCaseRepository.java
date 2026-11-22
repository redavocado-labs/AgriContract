package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;

import java.util.List;
import java.util.Optional;

/** BreachCase (V20 allegation, V21 decision): one per milestone. */
public interface BreachCaseRepository {

    /** One page of the Arbitrator queue and the total of the filter. */
    record Page(List<BreachCase> content, long totalElements) {
    }

    /** Inserts a new case, inside the caller's transaction. */
    void insert(BreachCase breachCase);

    /** Persists a changed case (status, decision, computed resolution); must hold the lock of {@link #findByIdForUpdate}. */
    void update(BreachCase breachCase);

    Optional<BreachCase> findByMilestoneId(String milestoneId);

    /** Plain read, no lock. */
    Optional<BreachCase> findById(String breachCaseId);

    Optional<BreachCase> findByIdForUpdate(String breachCaseId);

    /** Ids of the cases whose contest window is OPEN and has passed at {@code now}, oldest deadline first (GB-50). */
    List<String> findIdsWithExpiredContestWindow(java.time.Instant now, int limit);

    /**
     * The Arbitrator queue across all contracts, reportedAt newest first then breachCaseId. status may be null;
     * awaitingApproval keeps only cases that are not RESOLVED and carry a computedResolution.
     */
    Page findPage(BreachCaseStatus status, boolean awaitingApproval, int page, int size);
}
