package com.agricontract.inspection;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FreeCancelDeadlineMigrationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42")
            .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");
    @Container static final MySQLContainer<?> FRESH = new MySQLContainer<>("mysql:8.0.42")
            .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Test void freshDatabaseMigratesThroughV9WithoutCollisions() {
        var flyway = Flyway.configure().dataSource(FRESH.getJdbcUrl(), FRESH.getUsername(), FRESH.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(9);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
    }

    @Test void upgradesRealV8WithApprovedFourHourBackfill() {
        String url = MYSQL.getJdbcUrl();
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).target("8").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword()));
        var history = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        String id = UUID.randomUUID().toString();
        Instant assigned = Instant.parse("2026-10-23T03:00:00.123456Z");
        jdbc.update("""
                INSERT INTO level1_5_inspection_assignment
                (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,
                 report_due_business_days,report_due_at) VALUES (?,?,?,?,?,'contract-service',2,?)
                """, id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                Timestamp.from(assigned), Timestamp.from(assigned.plusSeconds(86400)));
        String endedId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO level1_5_inspection_assignment
                (commission_id,contract_id,milestone_id,inspector_id,assigned_at,ended_at,assigned_by_service,
                 report_due_business_days,report_due_at) VALUES (?,?,?,?,?,?,'contract-service',2,?)
                """, endedId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                Timestamp.from(assigned), Timestamp.from(assigned.plusSeconds(60)), Timestamp.from(assigned.plusSeconds(86400)));
        var before = jdbc.queryForMap("SELECT * FROM level1_5_inspection_assignment WHERE commission_id=?", id);
        var endedBefore = jdbc.queryForMap("SELECT * FROM level1_5_inspection_assignment WHERE commission_id=?", endedId);
        var flyway = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8') ORDER BY installed_rank")).isEqualTo(history);
        var after = jdbc.queryForMap("SELECT * FROM level1_5_inspection_assignment WHERE commission_id=?", id);
        assertThat(((Timestamp) after.get("free_cancel_until")).toInstant()).isEqualTo(assigned.plusSeconds(4 * 3600));
        after.remove("free_cancel_until");
        assertThat(after).isEqualTo(before);
        var endedAfter = jdbc.queryForMap("SELECT * FROM level1_5_inspection_assignment WHERE commission_id=?", endedId);
        assertThat(((Timestamp) endedAfter.remove("free_cancel_until")).toInstant()).isEqualTo(assigned.plusSeconds(4 * 3600));
        assertThat(endedAfter).isEqualTo(endedBefore);
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='level1_5_inspection_assignment' AND column_name='free_cancel_until'", String.class)).isEqualTo("NO");
        assertThatThrownBy(() -> jdbc.update("UPDATE level1_5_inspection_assignment SET free_cancel_until=NULL WHERE commission_id=?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO level1_5_inspection_assignment (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,report_due_business_days,report_due_at,free_cancel_until) VALUES (?,?,?,?,?,'test',2,?,NULL)", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), Timestamp.from(assigned), Timestamp.from(assigned.plusSeconds(86400)))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='9' AND success=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name='fk_l15_report_file_assignment'", Integer.class)).isEqualTo(1);
    }
}
