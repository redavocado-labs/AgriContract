package com.agricontract.reputation.application.port;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

/** Insert-only lock evidence; the caller owns the surrounding transaction. */
public interface LockEntryWriter {
    record Entry(UUID entryId, UUID sourceEventId, UUID remedyDecisionId, UUID contractId, UUID userId,
                 String penalizedRole, int baseDays, BigDecimal repeatMultiplier, BigDecimal trackMultiplier,
                 BigDecimal zeroProgressMultiplier, int durationDays, Instant decidedAt, Instant lockedUntil) { }

    void insert(Entry entry);

    record Existing(UUID sourceEventId, UUID remedyDecisionId, UUID contractId, UUID userId,
                    String penalizedRole, Instant decidedAt, UUID buyerId, UUID sellerId,
                    String reason, BigInteger settledMilestones) { }

    List<Existing> findBySourceOrDecision(UUID sourceEventId, UUID remedyDecisionId);

    void recordEvidence(UUID decisionId, UUID buyerId, UUID sellerId, String reason, BigInteger settledMilestones);
    Optional<UUID> decisionForEvent(UUID sourceEventId);
    void recordEvent(UUID sourceEventId, UUID decisionId);
}
