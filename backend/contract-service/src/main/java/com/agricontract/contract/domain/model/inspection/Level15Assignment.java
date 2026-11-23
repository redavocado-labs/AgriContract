package com.agricontract.contract.domain.model.inspection;

import java.time.Instant;
import java.util.Objects;

/**
 * inspection.level1_5_assigned (D57): inspection-service picked an inspector for the milestone. The latest assignment
 * of a milestone is the active one; until freeCancelUntil the inspection can be cancelled at no cost.
 */
public record Level15Assignment(String commissionId, String milestoneId, Instant assignedAt, Instant freeCancelUntil,
                                Instant reportDueAt) {
    public Level15Assignment {
        Objects.requireNonNull(commissionId, "commissionId");
        Objects.requireNonNull(milestoneId, "milestoneId");
        Objects.requireNonNull(assignedAt, "assignedAt");
        Objects.requireNonNull(freeCancelUntil, "freeCancelUntil");
        Objects.requireNonNull(reportDueAt, "reportDueAt");
    }
}
