package com.agricontract.reputation.application.dto;

import com.agricontract.reputation.domain.RemedyAttribution.Role;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/** A validated remedy.finalized decision. */
public record ProcessLockoutCommand(UUID sourceEventId, UUID remedyDecisionId, UUID contractId,
                                    UUID buyerId, UUID sellerId, Role finalBreachingRole,
                                    String breachReasonCode, boolean reputationEligible,
                                    BigInteger settledMilestoneCount, Instant decidedAt, String correlationId) {
    public ProcessLockoutCommand(UUID sourceEventId, UUID remedyDecisionId, UUID contractId,
                                 UUID buyerId, UUID sellerId, Role finalBreachingRole,
                                 String breachReasonCode, boolean reputationEligible,
                                 int settledMilestoneCount, Instant decidedAt, String correlationId) {
        this(sourceEventId, remedyDecisionId, contractId, buyerId, sellerId, finalBreachingRole,
                breachReasonCode, reputationEligible, BigInteger.valueOf(settledMilestoneCount), decidedAt, correlationId);
    }
}
