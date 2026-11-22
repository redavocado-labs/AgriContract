package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.usecase.RetryRemedyLegsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resends refused remedy legs whose retry is due (#249), one transaction per leg, so one that fails does not hold
 * back the others. Same poll interval and backoff as the settlement legs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemedyLegRetryScheduler {

    private final RetryRemedyLegsUseCase retries;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${escrow.deposit-lock.retry-poll-interval}")
    public void retryDueLegs() {
        for (String legId : retries.legsWithRetryDue()) {
            try {
                transactionTemplate.executeWithoutResult(status -> retries.retryDue(legId));
            } catch (RuntimeException e) {
                log.error("event=remedy_leg_retry_error remedyLegId={}: {}", legId, e.getMessage(), e);
            }
        }
    }
}
