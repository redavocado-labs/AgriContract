package com.agricontract.reputation.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Insert-only lock evidence; the caller owns the surrounding transaction. */
public interface LockEntryWriter {
    record Entry(UUID entryId, UUID sourceEventId, UUID remedyDecisionId, UUID contractId, UUID userId,
                 String penalizedRole, int baseDays, BigDecimal repeatMultiplier, BigDecimal trackMultiplier,
                 BigDecimal zeroProgressMultiplier, int durationDays, Instant decidedAt, Instant lockedUntil) { }

    void insert(Entry entry);

}
