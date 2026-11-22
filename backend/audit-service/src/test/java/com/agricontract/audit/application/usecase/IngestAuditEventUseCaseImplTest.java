package com.agricontract.audit.application.usecase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.application.port.TransactionRunner;
import com.agricontract.audit.domain.exception.InvalidAuditRecordException;
import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.audit.domain.repository.AuditRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestAuditEventUseCaseImplTest {

    static final Instant NOW = Instant.parse("2026-10-27T04:00:00Z");
    static final String HASH = "0123456789abcdef".repeat(4);

    /** Records inserts; can pretend a concurrent delivery won the race (insert says duplicate). */
    static class FakeRepository implements AuditRecordRepository {
        final List<AuditRecord> rows = new ArrayList<>();
        boolean raceOnInsert;

        @Override
        public boolean insert(AuditRecord record) {
            if (raceOnInsert) {
                rows.add(record); // the concurrent winner, same content
                return false;
            }
            rows.add(record);
            return true;
        }

        @Override
        public Optional<AuditRecord> findBySourceEventId(String sourceEventId) {
            return rows.stream().filter(r -> r.sourceEventId().equals(sourceEventId)).findFirst();
        }
    }

    final FakeRepository repository = new FakeRepository();
    final TransactionRunner transactions = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
    final IngestAuditEventUseCaseImpl useCase = new IngestAuditEventUseCaseImpl(repository, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));
    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(IngestAuditEventUseCaseImpl.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void newEventIsInsertedWithTheClockAsCreatedAt() {
        AuditIngestCommand command = command(UUID.randomUUID().toString(), Map.of("a", "1"));
        useCase.execute(command);

        assertThat(repository.rows).hasSize(1);
        AuditRecord row = repository.rows.get(0);
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(row.sourceEventId()).isEqualTo(command.sourceEventId());
        assertThat(row.subjectType()).isEqualTo(SubjectType.CONTRACT);
        assertThat(messages()).anyMatch(m -> m.startsWith("event=audit_record_ingested sourceType=CONTRACT_SIGNED"));
    }

    @Test
    void existingEventIsNotInsertedAgain() {
        String eventId = UUID.randomUUID().toString();
        useCase.execute(command(eventId, Map.of("a", "1")));
        useCase.execute(command(eventId, Map.of("a", "1")));

        assertThat(repository.rows).hasSize(1);
        assertThat(messages()).contains("event=audit_duplicate_ignored sourceEventId=" + eventId);
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void duplicateReportedByTheRepositoryIsANoOp() {
        repository.raceOnInsert = true;
        String eventId = UUID.randomUUID().toString();
        useCase.execute(command(eventId, Map.of("a", "1")));

        assertThat(repository.rows).hasSize(1);
        assertThat(messages()).contains("event=audit_duplicate_ignored sourceEventId=" + eventId);
        assertThat(messages()).noneMatch(m -> m.startsWith("event=audit_record_ingested"));
    }

    @Test
    void duplicateWithDifferentContentWarnsAndDoesNotOverwrite() {
        String eventId = UUID.randomUUID().toString();
        useCase.execute(command(eventId, Map.of("a", "1")));
        useCase.execute(command(eventId, Map.of("a", "2")));

        assertThat(repository.rows).hasSize(1);
        assertThat(repository.rows.get(0).content()).containsEntry("a", "1");
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().startsWith("event=audit_duplicate_content_mismatch"));
    }

    @Test
    void invalidIdentityIsRejectedBeforeAnythingIsWritten() {
        AuditIngestCommand bad = new AuditIngestCommand(SubjectType.CONTRACT, "c", "CONTRACT_SIGNED",
                "contract.signed", "not-a-uuid", null, null, HASH, NOW, Map.of("a", "1"));
        assertThatThrownBy(() -> useCase.execute(bad)).isInstanceOf(InvalidAuditRecordException.class);
        assertThat(repository.rows).isEmpty();
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    static AuditIngestCommand command(String eventId, Map<String, String> content) {
        return new AuditIngestCommand(SubjectType.CONTRACT, "contract-1", "CONTRACT_SIGNED", "contract.signed",
                eventId, "cause", "corr", HASH, NOW, content);
    }
}
