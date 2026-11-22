package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.usecase.RetrySettlementLegsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resends refused settlement legs whose retry is due (#248), one transaction per leg, so one that fails does not hold
 * back the others. Same poll interval and backoff as the deposit locks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementLegRetryScheduler {

    private final RetrySettlementLegsUseCase retries;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${escrow.deposit-lock.retry-poll-interval}")
    public void retryDueLegs() {
        for (String legId : retries.legsWithRetryDue()) {
            try {
                transactionTemplate.executeWithoutResult(status -> retries.retryDue(legId));
            } catch (RuntimeException e) {
                log.error("event=settlement_leg_retry_error legId={}: {}", legId, e.getMessage(), e);
            }
        }
    }
}
