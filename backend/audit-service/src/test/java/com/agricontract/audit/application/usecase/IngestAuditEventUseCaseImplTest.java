package com.agricontract.audit.application.usecase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.application.port.TransactionRunner;
import com.agricontract.audit.domain.exception.InvalidAuditRecordException;
import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.model.ChainedAuditRecord;
import com.agricontract.audit.domain.model.StoredAuditRecord;
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
        final List<ChainedAuditRecord> chained = new ArrayList<>();
        int locks;
        boolean raceOnInsert;

        @Override
        public void lockChain() {
            locks++;
        }

        @Override
        public Optional<String> latestHash() {
            return chained.isEmpty() ? Optional.empty() : Optional.of(chained.get(chained.size() - 1).recordHash());
        }

        @Override
        public Optional<String> latestHash(SubjectType type, String subjectId) {
            return chained.stream()
                    .filter(c -> c.record().subjectType() == type && c.record().subjectId().equals(subjectId))
                    .reduce((a, b) -> b).map(ChainedAuditRecord::recordHash);
        }

        @Override
        public boolean insert(ChainedAuditRecord record) {
            rows.add(record.record());
            chained.add(record);
            return !raceOnInsert; // raceOnInsert: the concurrent winner, same content
        }

        @Override
        public List<StoredAuditRecord> findAfter(long afterSeq, int limit) {
            return List.of();
        }

        @Override
        public List<StoredAuditRecord> findAfter(SubjectType type, String subjectId, long afterSeq, int limit) {
            return List.of();
        }

        @Override
        public List<StoredAuditRecord> findNewestFirst(SubjectType subjectType, int offset, int limit) {
            return List.of();
        }

        @Override
        public long count(SubjectType subjectType) {
            return 0;
        }

        @Override
        public Optional<StoredAuditRecord> findBySourceEventId(String sourceEventId) {
            return chained.stream().filter(c -> c.record().sourceEventId().equals(sourceEventId)).findFirst()
                    .map(c -> new StoredAuditRecord(c.record().recordId(), 1, c.recordHash(), c.prevHashGlobal(),
                            c.prevHashSubject(), c));
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
    void firstRecordHasNoPrevAndTheSecondPointsAtTheFirstGlobally() {
        useCase.execute(command(UUID.randomUUID().toString(), Map.of("a", "1")));
        useCase.execute(command(UUID.randomUUID().toString(), Map.of("a", "2")));

        ChainedAuditRecord first = repository.chained.get(0);
        ChainedAuditRecord second = repository.chained.get(1);
        assertThat(first.prevHashGlobal()).isNull();
        assertThat(first.prevHashSubject()).isNull();
        assertThat(second.prevHashGlobal()).isEqualTo(first.recordHash());
        assertThat(second.prevHashSubject()).isEqualTo(first.recordHash());
        assertThat(second.hashMatches()).isTrue();
        assertThat(repository.locks).isEqualTo(2);
    }

    @Test
    void subjectPrevOnlyComesFromTheSameSubject() {
        useCase.execute(commandFor("A", UUID.randomUUID().toString()));
        useCase.execute(commandFor("B", UUID.randomUUID().toString()));
        useCase.execute(commandFor("A", UUID.randomUUID().toString()));

        ChainedAuditRecord a1 = repository.chained.get(0);
        ChainedAuditRecord b1 = repository.chained.get(1);
        ChainedAuditRecord a2 = repository.chained.get(2);
        assertThat(b1.prevHashGlobal()).isEqualTo(a1.recordHash());
        assertThat(b1.prevHashSubject()).isNull();
        assertThat(a2.prevHashGlobal()).isEqualTo(b1.recordHash());
        assertThat(a2.prevHashSubject()).isEqualTo(a1.recordHash());
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

    static AuditIngestCommand commandFor(String subjectId, String eventId) {
        return new AuditIngestCommand(SubjectType.CONTRACT, subjectId, "CONTRACT_SIGNED", "contract.signed",
                eventId, "cause", "corr", HASH, NOW, Map.of("a", "1"));
    }

    static AuditIngestCommand command(String eventId, Map<String, Object> content) {
        return new AuditIngestCommand(SubjectType.CONTRACT, "contract-1", "CONTRACT_SIGNED", "contract.signed",
                eventId, "cause", "corr", HASH, NOW, content);
    }
}
