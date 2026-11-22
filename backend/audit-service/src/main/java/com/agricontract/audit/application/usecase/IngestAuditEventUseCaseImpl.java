package com.agricontract.audit.application.usecase;

import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.application.port.TransactionRunner;
import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.model.ChainedAuditRecord;
import com.agricontract.audit.domain.repository.AuditRecordRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Source event to one append-only, hash-chained audit record. One transaction: take the chain lock, dedup by
 * sourceEventId (the record itself is the inbox entry), read both chain tails, hash, insert. A repeat is a no-op,
 * never an error, never a second row; a repeat whose content differs is still not applied and is logged as a warning.
 * Wired as a bean in infrastructure.config (no Spring annotation here).
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

    /** Inserted, or the record that was already there (null when it vanished, which cannot happen append-only). */
    private record Outcome(boolean inserted, AuditRecord stored) {
    }

    @Override
    public void execute(AuditIngestCommand command) {
        AuditRecord record = new AuditRecord(UUID.randomUUID().toString(), command.subjectType(),
                command.subjectId(), command.sourceType(), command.sourceEventType(), command.sourceEventId(),
                command.causationId(), command.correlationId(), command.sourceHash(), command.occurredAt(),
                command.content(), clock.instant());

        Outcome outcome = transactions.inTransaction(() -> chainedInsert(record));
        if (!outcome.inserted()) {
            if (outcome.stored() != null) {
                duplicate(record, outcome.stored());
            } else {
                log.info("event=audit_duplicate_ignored sourceEventId={}", record.sourceEventId());
            }
            return;
        }
        log.info("event=audit_record_ingested sourceType={} sourceEventId={} subjectType={} subjectId={}",
                record.sourceType(), record.sourceEventId(), record.subjectType(), record.subjectId());
    }

    private Outcome chainedInsert(AuditRecord record) {
        records.lockChain();
        Optional<AuditRecord> existing = records.findBySourceEventId(record.sourceEventId());
        if (existing.isPresent()) {
            return new Outcome(false, existing.get());
        }
        String prevGlobal = records.latestHash().orElse(null);
        String prevSubject = records.latestHash(record.subjectType(), record.subjectId()).orElse(null);
        if (records.insert(ChainedAuditRecord.chain(record, prevGlobal, prevSubject))) {
            return new Outcome(true, null);
        }
        // Lost to a duplicate key despite the lock (e.g. a writer that bypasses it): still just a replay.
        return new Outcome(false, records.findBySourceEventId(record.sourceEventId()).orElse(null));
    }

    private void duplicate(AuditRecord incoming, AuditRecord stored) {
        log.info("event=audit_duplicate_ignored sourceEventId={}", incoming.sourceEventId());
        if (!stored.content().equals(incoming.content())) {
            log.warn("event=audit_duplicate_content_mismatch sourceEventId={} sourceType={}",
                    incoming.sourceEventId(), incoming.sourceType());
        }
    }
}
