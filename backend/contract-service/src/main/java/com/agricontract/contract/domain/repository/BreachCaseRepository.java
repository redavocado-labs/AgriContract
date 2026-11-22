package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.breach.BreachCase;

import java.util.Optional;

/** BreachCase allegations (V20): one per milestone. */
public interface BreachCaseRepository {

    /** Inserts a new case, inside the caller's transaction. */
    void insert(BreachCase breachCase);

    Optional<BreachCase> findByMilestoneId(String milestoneId);
}
