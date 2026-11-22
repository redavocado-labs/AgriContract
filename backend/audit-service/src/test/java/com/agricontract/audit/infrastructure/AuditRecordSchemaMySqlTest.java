package com.agricontract.audit.infrastructure;

import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.audit.infrastructure.persistence.JdbcAuditRecordRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Audit invariants enforced by the real database: production migration plus the actual runtime grants. */
class AuditRecordSchemaMySqlTest {

    static final String HASH = "0123456789abcdef".repeat(4);
    static final String UTC = "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42")
            .withDatabaseName("audit_db").withUsername("audit_migration")
            .withPassword("test-only-migration-password").withEnv("MYSQL_ROOT_HOST", "%");
    static JdbcTemplate runtime;
    static JdbcAuditRecordRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        MYSQL.start();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        admin.execute("CREATE USER 'audit_runtime'@'%' IDENTIFIED BY 'test-only-runtime-password'");
        String sql = Files.readString(Path.of("ops/grants.sql")).replaceAll("(?m)--[^\\r\\n]*", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) admin.execute(statement);
        DriverManagerDataSource source = new DriverManagerDataSource(MYSQL.getJdbcUrl() + UTC, "audit_runtime",
                "test-only-runtime-password");
        runtime = new JdbcTemplate(source);
        repository = new JdbcAuditRecordRepository(runtime);
    }

    @Test
    void runsAsTheRestrictedRuntimeAccount() {
        assertThat(runtime.queryForObject("SELECT CURRENT_USER()", String.class)).isEqualTo("audit_runtime@%");
    }

    @Test
    void runtimeAccountCannotUpdateOrDelete() {
        String eventId = UUID.randomUUID().toString();
        repository.insert(record(eventId, Map.of("a", "1")));
        assertThatThrownBy(() -> runtime.update("UPDATE audit_record SET subject_id = 'x' WHERE source_event_id = ?", eventId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtime.update("DELETE FROM audit_record WHERE source_event_id = ?", eventId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void insertsAndReadsBackEveryTypedColumn() {
        String eventId = UUID.randomUUID().toString();
        AuditRecord written = record(eventId, Map.of("contractId", "c-1", "reservedQuantity", "1250.50"));
        assertThat(repository.insert(written)).isTrue();

        AuditRecord read = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(read).isEqualTo(written);
        assertThat(runtime.queryForObject("SELECT source_type FROM audit_record WHERE source_event_id = ?",
                String.class, eventId)).isEqualTo("CONTRACT_SIGNED");
        assertThat(read.content()).containsEntry("reservedQuantity", "1250.50");
    }

    @Test
    void nullableColumnsStayNull() {
        String eventId = UUID.randomUUID().toString();
        AuditRecord written = new AuditRecord(UUID.randomUUID().toString(), SubjectType.SYSTEM, "sys", "OTHER", "a.b",
                eventId, null, null, null, Instant.parse("2026-10-27T03:00:00.123456Z"), Map.of("k", "v"),
                Instant.parse("2026-10-27T04:00:00.654321Z"));
        repository.insert(written);
        assertThat(repository.findBySourceEventId(eventId).orElseThrow()).isEqualTo(written);
    }

    @Test
    void secondInsertOfTheSameSourceEventIdLeavesOneRow() {
        String eventId = UUID.randomUUID().toString();
        assertThat(repository.insert(record(eventId, Map.of("a", "1")))).isTrue();
        assertThat(repository.insert(record(eventId, Map.of("a", "2")))).isFalse();

        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM audit_record WHERE source_event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(repository.findBySourceEventId(eventId).orElseThrow().content()).containsEntry("a", "1");
    }

    @Test
    void databaseUniqueRejectsARawDuplicate() {
        String eventId = UUID.randomUUID().toString();
        rawInsert(eventId, "CONTRACT", null);
        assertThatThrownBy(() -> rawInsert(eventId, "CONTRACT", null))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uk_audit_record_source_event");
    }

    @Test
    void checkRejectsABadSourceHash() {
        assertThatThrownBy(() -> rawInsert(UUID.randomUUID().toString(), "CONTRACT", "ABC"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_audit_record_source_hash");
        assertThatThrownBy(() -> rawInsert(UUID.randomUUID().toString(), "CONTRACT", HASH.toUpperCase()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_audit_record_source_hash");
    }

    @Test
    void checkRejectsABadSubjectType() {
        assertThatThrownBy(() -> rawInsert(UUID.randomUUID().toString(), "WIZARD", null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_audit_record_subject_type");
    }

    private static void rawInsert(String eventId, String subjectType, String hash) {
        runtime.update("""
                INSERT INTO audit_record (record_id, subject_type, subject_id, source_type, source_event_type,
                    source_event_id, source_hash, occurred_at, content, created_at)
                VALUES (?, ?, 's', 'CONTRACT_SIGNED', 'contract.signed', ?, ?, CURRENT_TIMESTAMP(6), '{"k":"v"}',
                    CURRENT_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), subjectType, eventId, hash);
    }

    private static AuditRecord record(String eventId, Map<String, String> content) {
        return new AuditRecord(UUID.randomUUID().toString(), SubjectType.CONTRACT, "contract-1", "CONTRACT_SIGNED",
                "contract.signed", eventId, "cause", "corr", HASH, Instant.parse("2026-10-27T03:00:00Z"), content,
                Instant.parse("2026-10-27T04:00:00Z"));
    }
}
