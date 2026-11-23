package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.inspection.Level15Request;

import java.time.Instant;
import java.util.Optional;

/** level1_5_request (V26): every inspection.level1_5_requested written, and how it ended. */
public interface Level15RequestRepository {

    void insert(Level15Request request);

    /** The milestone's latest request that is still open, if any. */
    Optional<Level15Request> findLatestOpen(String milestoneId);

    /** Every open request of the milestone ends with {@code reason} at {@code at}; returns how many did. */
    int endOpen(String milestoneId, Level15Request.EndReason reason, Instant at);
}
