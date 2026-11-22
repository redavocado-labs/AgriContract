package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.service.EvidenceAttachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends confirm-attached again for named evidence whose first attempt failed (design §3.2.1 "Giữ file"). */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceAttachmentScheduler {
    private final EvidenceAttachment attachment;

    @Scheduled(fixedDelayString = "${contract.milestone.evidence-attach-retry-interval:PT5M}")
    public void retry() {
        try {
            int confirmed = attachment.retryPending();
            if (confirmed > 0) {
                log.info("event=evidence_confirm_attached_retried count={}", confirmed);
            }
        } catch (RuntimeException e) {
            log.error("event=evidence_confirm_attached_poll_failed reason={}", e.getMessage(), e);
        }
    }
}
