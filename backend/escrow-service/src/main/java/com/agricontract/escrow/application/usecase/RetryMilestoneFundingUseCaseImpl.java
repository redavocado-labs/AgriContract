package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.port.MilestoneLockCommandPublisher;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Resends a failed milestone lock when its retry is due (D69): the original request, same eventId and payload, so
 * bank-service dedups on it and re-evaluates a request it refused (D27, D39). Under the milestone's row lock, so a
 * result arriving meanwhile is never overwritten.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryMilestoneFundingUseCaseImpl implements RetryMilestoneFundingUseCase {

    private static final int BATCH = 100;

    private final EscrowMilestoneRepository milestones;
    private final MilestoneLockCommandPublisher lockCommands;
    private final Clock clock;

    @Override
    public List<String> milestonesWithRetryDue() {
        return milestones.findWithRetryDue(clock.instant(), BATCH);
    }

    @Override
    public boolean retryDue(String milestoneId) {
        Instant now = clock.instant();
        Optional<EscrowMilestone> found = milestones.findByIdForUpdate(milestoneId);
        if (found.isEmpty() || !found.get().isRetryDue(now)) {
            return false;
        }
        EscrowMilestone milestone = found.get();
        milestone.retryRequested(now);
        milestones.update(milestone);
        lockCommands.resendMilestoneLock(milestone);
        log.info("event=milestone_lock_retried contractId={} milestoneId={} failures={} sourceEventId={}",
                milestone.getContractId(), milestoneId, milestone.getFailedAttempts(), milestone.getRequestEventId());
        return true;
    }
}
