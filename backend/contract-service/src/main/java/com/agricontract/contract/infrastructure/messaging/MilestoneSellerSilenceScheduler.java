package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.usecase.SellerSilenceMilestonesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for AWAITING_SELLER_RESPONSE milestones whose response window has passed (D82). One failing milestone does not stop the next. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneSellerSilenceScheduler {
    private final SellerSilenceMilestonesUseCase sellerSilence;

    @Scheduled(fixedDelayString = "${contract.milestone.seller-silence-poll-interval:PT1M}")
    public void poll() {
        try {
            int handled = sellerSilence.execute();
            if (handled > 0) {
                log.info("event=milestone_seller_silence_handled count={}", handled);
            }
        } catch (RuntimeException e) {
            log.error("event=milestone_seller_silence_poll_failed reason={}", e.getMessage(), e);
        }
    }
}
