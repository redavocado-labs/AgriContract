package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.usecase.AutoConfirmMilestonesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for BUYER_RECEIVED milestones whose confirm window has passed (D78). One failing milestone does not stop the next. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneAutoConfirmScheduler {
    private final AutoConfirmMilestonesUseCase autoConfirm;

    @Scheduled(fixedDelayString = "${contract.milestone.auto-confirm-poll-interval:PT1M}")
    public void poll() {
        try {
            int settled = autoConfirm.execute();
            if (settled > 0) {
                log.info("event=milestone_auto_confirmed count={}", settled);
            }
        } catch (RuntimeException e) {
            log.error("event=milestone_auto_confirm_poll_failed reason={}", e.getMessage(), e);
        }
    }
}
