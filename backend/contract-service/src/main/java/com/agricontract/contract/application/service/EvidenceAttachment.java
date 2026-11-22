package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Keeps named evidence alive (design §3.2.1 "Giữ file"): once a declaration names a file, contract-service tells
 * file-service with confirm-attached, after its own commit. A failure is only logged; the files stay pending and
 * {@link #retryPending} sends them again, well within file-service's one-week orphan cleanup. A file that was
 * uploaded but never named is not confirmed, so file-service removes it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceAttachment {
    static final int BATCH = 50;

    private final EvidenceFileStore files;
    private final MilestoneEvidenceRepository evidence;
    private final TransactionRunner transactions;
    private final Clock clock;

    /** Best effort, outside any transaction; returns how many were confirmed. */
    public int confirmNow(List<String> fileIds) {
        int confirmed = 0;
        for (String fileId : fileIds) {
            try {
                files.confirmAttached(fileId);
                Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
                transactions.inTransaction(() -> evidence.markAttached(fileId, now));
                confirmed++;
            } catch (RuntimeException e) {
                log.warn("event=evidence_confirm_attached_failed fileId={} reason={}", fileId, e.getMessage());
            }
        }
        return confirmed;
    }

    /** The named files still waiting for confirm-attached, oldest first. */
    public int retryPending() {
        return confirmNow(evidence.findNamedNotAttached(BATCH));
    }
}
