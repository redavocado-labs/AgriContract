package com.agricontract.inspection.infrastructure.files;

import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.infrastructure.persistence.JdbcReportFileBindings;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** One retry batch. The remote operation precedes the local acknowledgement transaction. */
public final class FileAttachmentRecovery {
    private final JdbcReportFileBindings bindings;
    private final FileServiceClient files;
    private final TransactionRunner transactions;
    private final Clock clock;

    public FileAttachmentRecovery(JdbcReportFileBindings bindings, FileServiceClient files,
                                  TransactionRunner transactions, Clock clock) {
        this.bindings = Objects.requireNonNull(bindings);
        this.files = Objects.requireNonNull(files);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public int retryBatch(int limit) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Attachment confirmation must run after the report transaction");
        int confirmed = 0;
        for (String fileId : bindings.pendingAttachments(limit)) {
            try {
                // A stable idempotency key makes overlapping workers and lost acknowledgements safe.
                files.confirmAttached(fileId);
                transactions.inTransaction(() -> bindings.markAttached(fileId, clock.instant().truncatedTo(ChronoUnit.MICROS)));
                confirmed++;
            } catch (FileServiceClient.Unavailable unavailable) {
                // Keep the row pending; the next scheduled batch can retry it.
            }
        }
        return confirmed;
    }

    public void confirmOne(String fileId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Attachment confirmation must run after the report transaction");
        if (!bindings.isPendingAttachment(fileId)) return;
        try {
            files.confirmAttached(fileId);
            transactions.inTransaction(() -> bindings.markAttached(fileId, clock.instant().truncatedTo(ChronoUnit.MICROS)));
        } catch (FileServiceClient.Unavailable unavailable) {
            // The still-null timestamp is the durable retry marker.
        }
    }
}
