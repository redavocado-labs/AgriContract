package com.agricontract.reputation.application.dto;

import com.agricontract.reputation.domain.RemedyAttribution.Role;
import java.time.Instant;
import java.util.UUID;

/** A validated remedy.finalized decision. */
public record ProcessLockoutCommand(UUID sourceEventId, UUID remedyDecisionId, UUID contractId,
                                    UUID buyerId, UUID sellerId, Role finalBreachingRole,
                                    String breachReasonCode, boolean reputationEligible,
                                    int settledMilestoneCount, Instant decidedAt, String correlationId) { }
