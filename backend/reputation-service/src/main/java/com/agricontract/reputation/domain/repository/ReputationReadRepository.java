package com.agricontract.reputation.domain.repository;
import com.agricontract.reputation.domain.model.LockHistoryEntry;
import com.agricontract.reputation.domain.model.ReputationReadProjection;
import com.agricontract.reputation.domain.model.FlagOutcomeCounts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface ReputationReadRepository {
    /** One projection per requested ID, including IDs without facts; a bounded aggregate read. */
    List<ReputationReadProjection> publicSummaries(List<UUID> userIds, Instant now);
    FlagOutcomeCounts flagOutcomeCounts(UUID userId);
    List<LockHistoryEntry> lockHistory(UUID userId, Instant now);
}
