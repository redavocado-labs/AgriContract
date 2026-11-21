package com.agricontract.reputation.application.port;

import java.time.Instant;
import java.util.UUID;

/** Historical facts currently available at processing time, filtered by decision time. */
public interface LockHistoryReader {
    record Counts(long completed, long priorLocks, long recentLocks) { }

    Counts counts(UUID userId, Instant decidedAt);
}
