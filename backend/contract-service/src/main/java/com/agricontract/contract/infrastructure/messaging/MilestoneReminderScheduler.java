package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.usecase.SendMilestoneRemindersUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for the BF-04 reminders (D96): the buyer receive reminder and the funding reminder. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneReminderScheduler {
    private final SendMilestoneRemindersUseCase reminders;

    @Scheduled(fixedDelayString = "${contract.milestone.reminder-poll-interval:PT5M}")
    public void poll() {
        try {
            int receive = reminders.remindBuyersToReceive();
            int funding = reminders.remindBuyersToFund();
            if (receive + funding > 0) {
                log.info("event=milestone_reminders_written receive={} funding={}", receive, funding);
            }
        } catch (RuntimeException e) {
            log.error("event=milestone_reminder_poll_failed reason={}", e.getMessage(), e);
        }
    }
}
