package com.agricontract.reputation.application.dto;
import com.agricontract.reputation.domain.model.FlagOutcomeCounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record PublicSummaryResult(UUID userId, BigDecimal score, boolean locked, List<LockResult> lockHistory, FlagOutcomeCounts flagOutcomeCounts) {
    public PublicSummaryResult { lockHistory = List.copyOf(lockHistory); }
    public record LockResult(Instant lockedAt, Instant lockedUntil, int lockDurationDays,
                             String penalizedRole, String status) {}
}
