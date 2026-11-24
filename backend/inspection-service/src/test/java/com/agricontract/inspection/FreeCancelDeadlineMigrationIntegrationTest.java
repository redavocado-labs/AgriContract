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

    @Test void upgradesRealV8WithoutInventingHistoricalDeadlines() {
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
        var before = jdbc.queryForMap("SELECT * FROM level1_5_inspection_assignment WHERE commission_id=?", id);
        var flyway = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8') ORDER BY installed_rank")).isEqualTo(history);
        var after = jdbc.queryForMap("SELECT * FROM level1_5_inspection_assignment WHERE commission_id=?", id);
        assertThat(after).containsEntry("free_cancel_until", null);
        after.remove("free_cancel_until");
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='9' AND success=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name='fk_l15_report_file_assignment'", Integer.class)).isEqualTo(1);
    }
}
