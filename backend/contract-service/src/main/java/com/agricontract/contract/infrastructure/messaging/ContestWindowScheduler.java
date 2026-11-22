package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.usecase.ExpireContestWindowsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for breach cases whose contest window (GB-50) has passed with no contest. One failing case does not stop the next. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContestWindowScheduler {
    private final ExpireContestWindowsUseCase expire;

    @Scheduled(fixedDelayString = "${contract.breach.contest-expiry-poll-interval:PT5M}")
    public void poll() {
        try {
            int handled = expire.execute();
            if (handled > 0) {
                log.info("event=contest_windows_expired count={}", handled);
            }
        } catch (RuntimeException e) {
            log.error("event=contest_window_poll_failed reason={}", e.getMessage(), e);
        }
    }
}
