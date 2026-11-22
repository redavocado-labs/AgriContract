package com.agricontract.audit.application.usecase;

import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.application.port.TransactionRunner;
import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.repository.AuditRecordRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.UUID;

/**
 * Source event to one append-only audit record. sourceEventId is the dedup key and the record itself is the inbox
 * entry: a repeat (found up front, or lost to a concurrent insert) is a no-op, never an error, never a second row.
 * A repeat whose content differs is still not applied; it is logged as a warning. Wired as a bean in
 * infrastructure.config (no Spring annotation here).
 */
@Slf4j
public class IngestAuditEventUseCaseImpl implements IngestAuditEventUseCase {

    private final AuditRecordRepository records;
    private final TransactionRunner transactions;
    private final Clock clock;

    public IngestAuditEventUseCaseImpl(AuditRecordRepository records, TransactionRunner transactions, Clock clock) {
        this.records = records;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void execute(AuditIngestCommand command) {
        AuditRecord record = new AuditRecord(UUID.randomUUID().toString(), command.subjectType(),
                command.subjectId(), command.sourceType(), command.sourceEventType(), command.sourceEventId(),
                command.causationId(), command.correlationId(), command.sourceHash(), command.occurredAt(),
                command.content(), clock.instant());

        var existing = records.findBySourceEventId(record.sourceEventId());
        if (existing.isPresent()) {
            duplicate(record, existing.get());
            return;
        }
        boolean inserted = transactions.inTransaction(() -> records.insert(record));
        if (!inserted) {
            // A concurrent delivery committed first.
            records.findBySourceEventId(record.sourceEventId()).ifPresentOrElse(
                    winner -> duplicate(record, winner),
                    () -> log.info("event=audit_duplicate_ignored sourceEventId={}", record.sourceEventId()));
            return;
        }
        log.info("event=audit_record_ingested sourceType={} sourceEventId={} subjectType={} subjectId={}",
                record.sourceType(), record.sourceEventId(), record.subjectType(), record.subjectId());
    }

    private void duplicate(AuditRecord incoming, AuditRecord stored) {
        log.info("event=audit_duplicate_ignored sourceEventId={}", incoming.sourceEventId());
        if (!stored.content().equals(incoming.content())) {
            log.warn("event=audit_duplicate_content_mismatch sourceEventId={} sourceType={}",
                    incoming.sourceEventId(), incoming.sourceType());
        }
    }
}
