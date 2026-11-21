package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.usecase.RetryMilestoneFundingUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resends failed milestone locks whose retry is due (#120, D69), one transaction per milestone, so one that fails
 * does not hold back the others. Same poll interval and backoff as the deposit locks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneFundingRetryScheduler {

    private final RetryMilestoneFundingUseCase retries;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${escrow.deposit-lock.retry-poll-interval}")
    public void retryDueMilestones() {
        for (String milestoneId : retries.milestonesWithRetryDue()) {
            try {
                transactionTemplate.executeWithoutResult(status -> retries.retryDue(milestoneId));
            } catch (RuntimeException e) {
                log.error("event=milestone_lock_retry_error milestoneId={}: {}", milestoneId, e.getMessage(), e);
            }
        }
    }
}
