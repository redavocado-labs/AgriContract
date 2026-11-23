package com.agricontract.contract.application.dto;

import java.time.Instant;

/** inspection.level1_5_assigned (D57), parsed: an inspector was assigned for the milestone's Level 1.5 inspection. */
public record Level15AssignedCommand(String contractId, String milestoneId, String commissionId, Instant assignedAt,
                                     Instant freeCancelUntil, Instant reportDueAt, String eventId) {
}
