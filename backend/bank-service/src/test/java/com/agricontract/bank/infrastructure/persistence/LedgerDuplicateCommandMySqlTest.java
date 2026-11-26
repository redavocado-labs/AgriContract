package com.agricontract.bank.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.agricontract.bank.application.dto.LedgerCommand;
import com.agricontract.bank.application.usecase.ProcessLedgerCommandUseCaseImpl;
import com.agricontract.bank.domain.exception.DuplicateLedgerEntryException;
import com.agricontract.bank.domain.model.EntryType;
import com.agricontract.bank.domain.model.FundType;
import com.agricontract.bank.domain.model.LedgerEntry;
import com.agricontract.bank.infrastructure.messaging.OutboxLedgerResultPublisher;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LedgerDuplicateCommandMySqlTest {

    private static final int ROUNDS = 20;
    private static final String RUNTIME_PASSWORD = "test-only-runtime-password";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42")
            .withDatabaseName("bank_db").withUsername("bank_migration")
            .withPassword("test-only-migration-password").withEnv("MYSQL_ROOT_HOST", "%");

    private static DriverManagerDataSource runtimeDataSource;
    private static JdbcTemplate runtime;

    @BeforeAll
    static void migrateAndGrantRuntimeAccess() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        admin.execute("CREATE USER 'bank_runtime'@'%' IDENTIFIED BY '" + RUNTIME_PASSWORD + "'");
        String sql = Files.readString(Path.of("ops/grants.sql")).replaceAll("(?m)--[^\\r\\n]*", "");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) admin.execute(statement);
        }
        runtimeDataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), "bank_runtime", RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeDataSource);
    }

    @Test
    void simultaneousDeliveriesRecordOneEntryAndConfirmItTwice() throws Exception {
        assertThat(runtime.queryForObject("SELECT CURRENT_USER()", String.class)).isEqualTo("bank_runtime@%");
        var clock = Clock.systemUTC();
        var publisher = new OutboxLedgerResultPublisher(new OutboxWriter(runtime, new EventEnvelopeCodec()), clock);
        var transactions = new SpringTransactionRunner(new DataSourceTransactionManager(runtimeDataSource));

        try (var workers = Executors.newFixedThreadPool(2)) {
            for (int round = 1; round <= ROUNDS; round++) {
                String eventId = UUID.randomUUID().toString();
                LedgerCommand command = new LedgerCommand(eventId, UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), null, UUID.randomUUID().toString(),
                        EntryType.LOCK_BUYER_DEPOSIT, FundType.BUYER_DEPOSIT, null, null,
                        new BigDecimal("40000000.00"), eventId);
                var ledger = new RacingLedger(runtime);
                var useCase = new ProcessLedgerCommandUseCaseImpl(ledger, publisher, transactions, clock);

                var first = workers.submit(() -> useCase.execute(command));
                var second = workers.submit(() -> useCase.execute(command));
                first.get(20, TimeUnit.SECONDS);
                second.get(20, TimeUnit.SECONDS);

                assertThat(ledger.duplicateAppends.get()).as("duplicate insert in round %d", round).isEqualTo(1);
                List<String> rows = runtime.queryForList(
                        "SELECT entry_id FROM ledger_entry WHERE source_event_id = ?", String.class, eventId);
                assertThat(rows).as("ledger entries in round %d", round).hasSize(1);
                var completed = new Result(command.operation().completed(), rows.getFirst());
                List<Result> results = runtime.query("""
                        SELECT event_type, JSON_UNQUOTE(JSON_EXTRACT(envelope, '$.payload.ledgerEntryId')) AS entry_id
                        FROM event_outbox
                        WHERE JSON_UNQUOTE(JSON_EXTRACT(envelope, '$.payload.sourceEventId')) = ?
                        """, (row, number) -> new Result(row.getString("event_type"), row.getString("entry_id")), eventId);
                assertThat(results).as("outbox results in round %d", round).containsExactly(completed, completed);
            }
        }
    }

    private record Result(String eventType, String entryId) {}

    private static final class RacingLedger extends JdbcLedgerEntryRepository {
        private final CountDownLatch emptyReads = new CountDownLatch(2);
        private final AtomicInteger duplicateAppends = new AtomicInteger();

        private RacingLedger(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public Optional<LedgerEntry> findBySourceEventId(String sourceEventId) {
            Optional<LedgerEntry> found = super.findBySourceEventId(sourceEventId);
            if (found.isEmpty()) {
                // Both prechecks must find no entry before either worker can attempt the insert.
                emptyReads.countDown();
                try {
                    assertThat(emptyReads.await(10, TimeUnit.SECONDS)).as("both prechecks completed").isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while synchronizing the ledger race", interrupted);
                }
            }
            return found;
        }

        @Override
        public void append(LedgerEntry entry) {
            try {
                super.append(entry);
            } catch (DuplicateLedgerEntryException duplicate) {
                duplicateAppends.incrementAndGet();
                throw duplicate;
            }
        }
    }
}
