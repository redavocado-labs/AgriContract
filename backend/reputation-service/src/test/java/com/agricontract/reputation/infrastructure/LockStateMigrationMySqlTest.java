package com.agricontract.reputation.infrastructure;

import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.outbox.OutboxWriter;
import com.agricontract.reputation.application.usecase.LockEventLifecycle;
import com.agricontract.reputation.infrastructure.messaging.OutboxLockEventPublisher;
import com.agricontract.reputation.infrastructure.persistence.JdbcLockStateStore;
import com.agricontract.reputation.infrastructure.persistence.SpringTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real V2-to-V3 upgrade on a disposable MySQL database. */
class LockStateMigrationMySqlTest {
    static MySQLContainer<?> MYSQL;
    static JdbcTemplate jdbc;

    @BeforeEach void start(TestInfo test) {
        MYSQL = new MySQLContainer<>("mysql:8.0.42")
                .withDatabaseName("migration_test").withUsername("migration_user").withPassword("migration_password")
                .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");
        MYSQL.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        if (!test.getTestMethod().orElseThrow().getName().equals("freshDatabaseAppliesExactlyFiveMigrations")) {
            flyway("2").migrate();
        }
    }
    @AfterEach void stop() { MYSQL.stop(); }
    static Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    record Lock(UUID entry, UUID decision, UUID source, UUID user, LocalDateTime until) {}
    Lock insert(UUID user, LocalDateTime until) {
        var lock = new Lock(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), user, until);
        jdbc.update("""
                INSERT INTO lock_entry (entry_id, source_event_id, remedy_decision_id, contract_id, user_id,
                penalized_role, repeat_offense_multiplier, track_record_multiplier, zero_progress_multiplier,
                lock_duration_days, locked_until) VALUES (?,?,?,?,?,'BUYER',1,1,1,30,?)
                """, lock.entry.toString(), lock.source.toString(), lock.decision.toString(), UUID.randomUUID().toString(),
                user.toString(), until);
        return lock;
    }
    void override(Lock lock) {
        jdbc.update("""
                INSERT INTO lock_override_event (event_id,lock_entry_id,override_type,reason,proposed_by,approved_by)
                VALUES (?,?,'UNLOCK_EARLY','approved',?,?)
                """, UUID.randomUUID().toString(), lock.entry.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void emptyLedgerIsNoOp() {
        flyway("3").migrate();
        assertThat(count("user_lock_state")).isZero();
        assertThat(count("event_outbox")).isZero();
    }

    @Test void upgradeBackfillsOnlyEffectiveLocksAndFirstEventStartsAtOne() throws Exception {
        LocalDateTime now = jdbc.queryForObject("SELECT UTC_TIMESTAMP()", LocalDateTime.class);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        insert(a, now.plusDays(10));
        LocalDateTime expected = now.plusDays(20);
        insert(a, expected);
        override(insert(a, now.plusDays(30)));
        insert(b, now.minusDays(10));
        override(insert(c, now.plusDays(40)));

        flyway("3").migrate();
        assertThat(count("user_lock_state")).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT user_id, lock_revision, effective_locked_until FROM user_lock_state");
        assertThat(row.get("user_id")).isEqualTo(a.toString());
        assertThat(((Number) row.get("lock_revision")).longValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT effective_locked_until FROM user_lock_state WHERE user_id=?",
                LocalDateTime.class, a.toString())).isEqualTo(expected);
        assertThat(count("event_outbox")).isZero();

        // Exercise the current lifecycle against the V3 projection populated above.
        jdbc.execute("CREATE TABLE user_lock_mutex (user_id CHAR(36) PRIMARY KEY)");
        var lifecycle = new LockEventLifecycle(new SpringTransactionRunner(new DataSourceTransactionManager(jdbc.getDataSource())),
                new JdbcLockStateStore(jdbc), new OutboxLockEventPublisher(new OutboxWriter(jdbc, new EventEnvelopeCodec())));
        UUID decision = UUID.randomUUID(), source = UUID.randomUUID();
        assertThat(lifecycle.lockCreated(a, decision, "NON_DELIVERY", "migration-test", () -> jdbc.update("""
                INSERT INTO lock_entry (entry_id,source_event_id,remedy_decision_id,contract_id,user_id,penalized_role,
                repeat_offense_multiplier,track_record_multiplier,zero_progress_multiplier,lock_duration_days,locked_until)
                VALUES (?,?,?,?,?,'BUYER',1,1,1,30,?)
                """, UUID.randomUUID().toString(), source.toString(), decision.toString(), UUID.randomUUID().toString(),
                a.toString(), now.plusDays(50)))).isTrue();
        assertThat(jdbc.queryForObject("SELECT lock_revision FROM user_lock_state WHERE user_id=?", Long.class, a.toString())).isEqualTo(1L);
        assertThat(count("event_outbox")).isEqualTo(1);
        var event = new ObjectMapper().readTree(jdbc.queryForObject("SELECT envelope FROM event_outbox", String.class));
        assertThat(event.path("payload").path("lockRevision").asLong()).isEqualTo(1L);
        assertThat(event.path("payload").path("lockedUntil").asText()).isEqualTo(now.plusDays(50).toInstant(java.time.ZoneOffset.UTC).toString());
    }

    @Test void v4AcceptsEmptyLedger() {
        flyway("3").migrate();
        flyway(null).migrate();
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lock_entry' AND column_name='decided_at'", String.class)).isEqualTo("NO");
    }

    @Test void v4RejectsLegacyLocksWithoutGuessingDecisionTime() {
        insert(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        flyway("3").migrate();
        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(count("lock_entry")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lock_entry' AND column_name='decided_at'", Integer.class)).isZero();
    }

    @Test void v5FinalSchemaSupportsUpgradeAndConstraints() {
        flyway("3").migrate();
        flyway(null).migrate();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='remedy_lock_evidence' AND column_name='settled_milestone_count'", String.class)).isEqualTo("int");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='remedy_lock_evidence' AND column_name='decision_fingerprint'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='remedy_lock_evidence' AND column_name='breach_reason_code'", String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name='remedy_lock_evidence' AND referenced_table_name IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lock_entry' AND index_name='idx_lock_user_decided'", Integer.class)).isEqualTo(2);
        for (String table : new String[]{"user_lock_mutex", "remedy_event_identity", "processed_event", "remedy_delivery_fingerprint"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table)).isEqualTo(1);
        }
        for (String table : new String[]{"lock_entry", "user_lock_state"}) {
            String column = table.equals("lock_entry") ? "locked_until" : "effective_locked_until";
            assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", String.class, table, column)).isEqualTo("datetime(6)");
        }
        String decision = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO remedy_lock_evidence VALUES (?,?,?,NULL,?,?)", decision, UUID.randomUUID().toString(), UUID.randomUUID().toString(), Integer.MAX_VALUE, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO remedy_lock_evidence VALUES (?,?,?,NULL,0,?)", decision, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "a".repeat(64))).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE remedy_lock_evidence SET settled_milestone_count=-1 WHERE remedy_decision_id=?", decision)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO remedy_event_identity VALUES (?,?)", UUID.randomUUID().toString(), UUID.randomUUID().toString())).hasRootCauseInstanceOf(java.sql.SQLException.class);
        jdbc.update("INSERT INTO remedy_event_identity VALUES (?,?)", UUID.randomUUID().toString(), decision);
        assertThatThrownBy(() -> jdbc.update("UPDATE remedy_lock_evidence SET decision_fingerprint=NULL WHERE remedy_decision_id=?", decision)).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test void freshDatabaseAppliesExactlyFiveMigrations() {
        flyway(null).migrate();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank", String.class)).containsExactly("1", "2", "3", "4", "5");
    }
}
