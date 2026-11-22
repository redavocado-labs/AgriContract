package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * file.ready / file.failed (D71): what file-service's scan said about a file. Runs in the consumer's transaction.
 * The outcome is kept by fileId even when no milestone evidence names that file (yet): file-service may report a
 * fast scan before the upload that produced the file is recorded here, and the upload then reads it. The first
 * outcome of a file stays.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordFileScanResultUseCase {
    private final MilestoneEvidenceRepository evidence;
    private final Clock clock;

    public void execute(MilestoneEvidenceCommands.ScanResult command) {
        boolean recorded = evidence.recordScanOutcome(command.fileId(), command.status(), command.failureReason(),
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        if (recorded) {
            log.info("event=file_scan_outcome_recorded fileId={} status={} eventId={}", command.fileId(),
                    command.status(), command.eventId());
        } else {
            log.warn("event=file_scan_outcome_already_known fileId={} status={} eventId={}", command.fileId(),
                    command.status(), command.eventId());
        }
    }
}
