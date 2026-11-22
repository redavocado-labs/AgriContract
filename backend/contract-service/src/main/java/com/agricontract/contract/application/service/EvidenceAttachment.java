package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository.PendingAttach;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Keeps named evidence alive (design §3.2.1 "Giữ file"): once a declaration names a file, contract-service tells
 * file-service with confirm-attached, after its own commit. A failure is only logged and the file's next attempt is
 * pushed back exponentially (5 minutes doubling, at most 6 hours, well within file-service's one-week orphan
 * cleanup); {@link #retryPending} picks the files whose next attempt is due, so a file that keeps failing never
 * starves newer ones. A file that was uploaded but never named is not confirmed, so file-service removes it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceAttachment {
    static final int BATCH = 50;
    static final Duration FIRST_BACKOFF = Duration.ofMinutes(5);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final EvidenceFileStore files;
    private final MilestoneEvidenceRepository evidence;
    private final TransactionRunner transactions;
    private final Clock clock;

    /** First attempt for just-named files, best effort, outside any transaction; returns how many were confirmed. */
    public int confirmNow(List<String> fileIds) {
        return confirm(fileIds.stream().map(id -> new PendingAttach(id, 0)).toList());
    }

    /** The named files whose next confirm-attached is due, the most overdue first. */
    public int retryPending() {
        return confirm(evidence.findAttachDue(now(), BATCH));
    }

    private int confirm(List<PendingAttach> pending) {
        int confirmed = 0;
        for (PendingAttach file : pending) {
            try {
                files.confirmAttached(file.fileId());
                Instant at = now();
                transactions.inTransaction(() -> evidence.markAttached(file.fileId(), at));
                confirmed++;
            } catch (RuntimeException e) {
                Instant next = now().plus(backoff(file.attempts()));
                log.warn("event=evidence_confirm_attached_failed fileId={} attempt={} nextAttemptAt={} reason={}",
                        file.fileId(), file.attempts() + 1, next, e.getMessage());
                try {
                    transactions.inTransaction(() -> evidence.recordAttachFailure(file.fileId(), next));
                } catch (RuntimeException recordFailed) {
                    log.error("event=evidence_attach_backoff_not_recorded fileId={} reason={}", file.fileId(),
                            recordFailed.getMessage(), recordFailed);
                }
            }
        }
        return confirmed;
    }

    /** 5 min, 10, 20 ... capped at 6 h, after {@code failedAttempts} earlier failures. */
    static Duration backoff(int failedAttempts) {
        int shift = Math.min(failedAttempts, 16);
        Duration delay = FIRST_BACKOFF.multipliedBy(1L << shift);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
