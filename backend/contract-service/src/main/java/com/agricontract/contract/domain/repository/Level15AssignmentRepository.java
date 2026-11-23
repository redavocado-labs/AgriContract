package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.inspection.Level15Assignment;

import java.util.Optional;

/** level1_5_assignment (V26): one row per commission; the latest per milestone is the active one. */
public interface Level15AssignmentRepository {

    /** Inserts the assignment; false when the commission is already stored. */
    boolean insertIfAbsent(Level15Assignment assignment);

    /** The milestone's latest assignment (assignedAt), if any. */
    Optional<Level15Assignment> findLatest(String milestoneId);
}
