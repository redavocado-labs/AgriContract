package com.agricontract.inspection.infrastructure.files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Repeats post-commit retention requests without holding a database lock during HTTP. */
@Component
@ConditionalOnProperty(name = "inspection.file-service.attachment-retry-enabled", havingValue = "true", matchIfMissing = true)
public class FileAttachmentRetryJob {
    private static final Logger LOG = LoggerFactory.getLogger(FileAttachmentRetryJob.class);
    private final FileAttachmentRecovery recovery;

    public FileAttachmentRetryJob(FileAttachmentRecovery recovery) { this.recovery = recovery; }

    @Scheduled(fixedDelayString = "${inspection.file-service.attachment-retry-interval-ms:3600000}")
    public void retry() {
        try { recovery.retryBatch(100); }
        catch (RuntimeException failure) { LOG.warn("Inspection file retention retry failed", failure); }
    }
}
