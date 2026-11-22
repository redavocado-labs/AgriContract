package com.agricontract.audit.infrastructure;

import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.application.dto.ChainVerificationResult;
import com.agricontract.audit.application.dto.ChainVerificationResult.Reason;
import com.agricontract.audit.application.usecase.IngestAuditEventUseCaseImpl;
import com.agricontract.audit.application.usecase.VerifyAuditChainUseCaseImpl;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.audit.infrastructure.persistence.JdbcAuditRecordRepository;
import com.agricontract.audit.infrastructure.persistence.SpringTransactionRunner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The hash chains on real MySQL: production migrations, the real runtime grants, tampering through a root account. */
class AuditHashChainMySqlTest {

    static final String HASH = "0123456789abcdef".repeat(4);
    static final String UTC = "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42")
            .withDatabaseName("audit_db").withUsername("audit_migration")
            .withPassword("test-only-migration-password").withEnv("MYSQL_ROOT_HOST", "%");
    static JdbcTemplate root;
    static JdbcTemplate runtime;
    static IngestAuditEventUseCaseImpl ingest;
    static VerifyAuditChainUseCaseImpl verify;

    @BeforeAll
    static void setUp() throws Exception {
        MYSQL.start();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        root = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl() + UTC, "root", MYSQL.getPassword()));
        root.execute("CREATE USER 'audit_runtime'@'%' IDENTIFIED BY 'test-only-runtime-password'");
        String sql = Files.readString(Path.of("ops/grants.sql")).replaceAll("(?m)--[^\\r\\n]*", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) root.execute(statement);
        DriverManagerDataSource source = new DriverManagerDataSource(MYSQL.getJdbcUrl() + UTC, "audit_runtime",
                "test-only-runtime-password");
        runtime = new JdbcTemplate(source);
        JdbcAuditRecordRepository repository = new JdbcAuditRecordRepository(runtime);
        ingest = new IngestAuditEventUseCaseImpl(repository,
                new SpringTransactionRunner(new DataSourceTransactionManager(source)), Clock.systemUTC());
        verify = new VerifyAuditChainUseCaseImpl(repository);
    }

    @BeforeEach
    void emptyTable() {
        root.execute("DELETE FROM audit_record");
    }

    @Test
    void designExampleChainsAreLinkedAndVerify() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        ingestFor(a);
        ingestFor(b);
        ingestFor(a);
        ingestFor(b);

        List<Map<String, Object>> rows = root.queryForList(
                "SELECT subject_id, record_hash, prev_hash_global, prev_hash_subject FROM audit_record ORDER BY global_seq");
        assertThat(rows).hasSize(4);
        String h1 = (String) rows.get(0).get("record_hash");
        String h2 = (String) rows.get(1).get("record_hash");
        String h3 = (String) rows.get(2).get("record_hash");
        assertThat(rows.get(0).get("prev_hash_global")).isNull();
        assertThat(rows.get(1).get("prev_hash_global")).isEqualTo(h1);
        assertThat(rows.get(2).get("prev_hash_global")).isEqualTo(h2);
        assertThat(rows.get(3).get("prev_hash_global")).isEqualTo(h3);
        assertThat(rows.get(0).get("prev_hash_subject")).isNull();
        assertThat(rows.get(1).get("prev_hash_subject")).isNull();
        assertThat(rows.get(2).get("prev_hash_subject")).isEqualTo(h1);
        assertThat(rows.get(3).get("prev_hash_subject")).isEqualTo(h2);

        ChainVerificationResult global = verify.verifyGlobal();
        assertThat(global.valid()).isTrue();
        assertThat(global.checkedCount()).isEqualTo(4);
        assertThat(verify.verifySubject(SubjectType.CONTRACT, a).valid()).isTrue();
        assertThat(verify.verifySubject(SubjectType.CONTRACT, b).checkedCount()).isEqualTo(2);
    }

    @Test
    void changedContentIsAHashMismatchOnThatRecord() {
        seed();
        String target = recordId(2);
        root.update("UPDATE audit_record SET content = JSON_SET(content, '$.a', 'tampered') WHERE record_id = ?", target);

        ChainVerificationResult result = verify.verifyGlobal();
        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(v -> v.recordId() + ":" + v.reason())
                .containsExactly(target + ":" + Reason.HASH_MISMATCH);
    }

    @Test
    void changedSubjectIdIsDetected() {
        seed();
        String target = recordId(1);
        root.update("UPDATE audit_record SET subject_id = 'someone-else' WHERE record_id = ?", target);

        assertThat(verify.verifyGlobal().violations()).extracting(v -> v.recordId() + ":" + v.reason())
                .contains(target + ":" + Reason.HASH_MISMATCH);
    }

    @Test
    void changedPrevHashSubjectIsAHashMismatch() {
        seed();
        String target = recordId(3);
        root.update("UPDATE audit_record SET prev_hash_subject = ? WHERE record_id = ?", "f".repeat(64), target);

        assertThat(verify.verifyGlobal().violations()).extracting(v -> v.recordId() + ":" + v.reason())
                .contains(target + ":" + Reason.HASH_MISMATCH);
    }

    @Test
    void deletingAMiddleRecordBreaksTheNextOnesGlobalPointer() {
        seed();
        String next = recordId(3);
        root.update("DELETE FROM audit_record WHERE record_id = ?", recordId(2));

        ChainVerificationResult result = verify.verifyGlobal();
        assertThat(result.valid()).isFalse();
        assertThat(result.checkedCount()).isEqualTo(3);
        assertThat(result.violations()).extracting(v -> v.recordId() + ":" + v.reason())
                .containsExactly(next + ":" + Reason.PREV_GLOBAL_MISMATCH);
    }

    @Test
    void deletingASubjectsRecordBreaksItsSubjectChain() {
        seed(); // A, B, A, B
        String next = recordId(3);
        root.update("DELETE FROM audit_record WHERE record_id = ?", recordId(1));
        // Record 3 is A's second one: its prevHashSubject now points at a record that is gone.
        ChainVerificationResult result = verify.verifySubject(SubjectType.CONTRACT, subjectOf(next));
        assertThat(result.violations()).extracting(v -> v.recordId() + ":" + v.reason())
                .containsExactly(next + ":" + Reason.PREV_SUBJECT_MISMATCH);
    }

    @Test
    void unreadableRowIsReportedAndTheWalkContinues() {
        seed();
        String target = recordId(2);
        root.update("UPDATE audit_record SET content = ? WHERE record_id = ?", "{\"totalAmount\": 1500000}", target);

        ChainVerificationResult global = verify.verifyGlobal();
        assertThat(global.valid()).isFalse();
        assertThat(global.checkedCount()).isEqualTo(4);
        assertThat(global.violations()).extracting(v -> v.recordId() + ":" + v.reason())
                .containsExactly(target + ":" + Reason.RECORD_UNREADABLE);
        assertThat(verify.verifySubject(SubjectType.CONTRACT, subjectOf(target)).violations())
                .extracting(v -> v.reason()).containsExactly(Reason.RECORD_UNREADABLE);
    }

    @Test
    void duplicateOfAnUnreadableRowDoesNotCrashIngest() {
        String subject = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        ingest.execute(command(subject, eventId));
        root.update("UPDATE audit_record SET content = ? WHERE source_event_id = ?", "{\"a\": 1}", eventId);
        ingest.execute(command(subject, eventId));

        assertThat(root.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentIngestsNeverShareAPreviousHash() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            String subject = UUID.randomUUID().toString();
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    ingestFor(subject);
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(root.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class)).isEqualTo(80);
        Set<String> prevs = new HashSet<>(root.queryForList(
                "SELECT COALESCE(prev_hash_global, 'NONE') FROM audit_record", String.class));
        assertThat(prevs).hasSize(80);
        ChainVerificationResult result = verify.verifyGlobal();
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isEqualTo(80);
    }

    @Test
    void duplicateEventIdUnderTheLockWritesOneRow() {
        String subject = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        ingest.execute(command(subject, eventId));
        ingest.execute(command(subject, eventId));

        assertThat(root.queryForObject("SELECT COUNT(*) FROM audit_record WHERE source_event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
        assertThat(verify.verifyGlobal().valid()).isTrue();
    }

    @Test
    void verifyPagesThroughMoreRecordsThanOnePage() {
        String subject = UUID.randomUUID().toString();
        for (int i = 0; i < 520; i++) {
            ingestFor(subject);
        }
        ChainVerificationResult result = verify.verifyGlobal();
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isEqualTo(520);
    }

    /** A, B, A, B. */
    private void seed() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        ingestFor(a);
        ingestFor(b);
        ingestFor(a);
        ingestFor(b);
    }

    private static String recordId(int position) {
        return root.queryForObject("SELECT record_id FROM audit_record ORDER BY global_seq LIMIT 1 OFFSET ?",
                String.class, position - 1);
    }

    private static String subjectOf(String recordId) {
        return root.queryForObject("SELECT subject_id FROM audit_record WHERE record_id = ?", String.class, recordId);
    }

    private static void ingestFor(String subject) {
        ingest.execute(command(subject, UUID.randomUUID().toString()));
    }

    private static AuditIngestCommand command(String subject, String eventId) {
        return new AuditIngestCommand(SubjectType.CONTRACT, subject, "CONTRACT_SIGNED", "contract.signed", eventId,
                "cause", "corr", HASH, Instant.parse("2026-11-20T08:00:00.123456Z"), Map.of("a", "1", "b", "2"));
    }
}
