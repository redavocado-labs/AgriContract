package com.agricontract.contract.domain.model.inspection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One inspection.level1_5_requested contract-service wrote for a milestone (D92), open until a report for the
 * milestone is stored (REPORTED) or the dispute ends first (CANCELLED, inspection.level1_5_cancelled).
 *
 * @param initiatedByActorId the party who asked (the contesting seller), null when the system asked
 */
public record Level15Request(String requestId, String milestoneId, InspectionRequestReason reason,
                             String initiatedByActorId, Instant requestedAt, Instant endedAt, EndReason endReason) {
    public enum EndReason {
        REPORTED,
        CANCELLED
    }

    public Level15Request {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(milestoneId, "milestoneId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if ((endedAt == null) != (endReason == null)) {
            throw new IllegalArgumentException("a request has endedAt exactly when it has an endReason");
        }
    }

    public static Level15Request open(String milestoneId, InspectionRequestReason reason, String initiatedByActorId,
                                      Instant now) {
        return new Level15Request(UUID.randomUUID().toString(), milestoneId, reason, initiatedByActorId, now, null,
                null);
    }

    public boolean isOpen() {
        return endedAt == null;
    }
}
