package com.agricontract.inspection;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SignatureMigrationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42");

    @Test void upgradeFromV5PreservesHistoryAndCreatesSignatureTable() {
        String url = MYSQL.getJdbcUrl();
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).target("5").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword()));
        var before = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var flyway = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).target("6").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5') ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inspector_signature", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inspector_signature' AND COLUMN_NAME='report_id'", String.class))
                .isEqualTo("ascii_bin");
        String commission = java.util.UUID.randomUUID().toString();
        String contract = java.util.UUID.randomUUID().toString();
        String milestone = java.util.UUID.randomUUID().toString();
        String inspector = java.util.UUID.randomUUID().toString();
        String report = java.util.UUID.randomUUID().toString();
        var now = java.sql.Timestamp.from(java.time.Instant.parse("2026-10-25T03:00:00Z"));
        var due = java.sql.Timestamp.from(java.time.Instant.parse("2026-10-26T03:00:00Z"));
        jdbc.update("INSERT INTO level1_5_inspection_assignment (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,report_due_business_days,report_due_at) VALUES (?,?,?,?,?,'test',1,?)",
                commission, contract, milestone, inspector, now, due);
        jdbc.update("INSERT INTO inspection_report (report_id,l15_commission_id,contract_id,milestone_id,inspector_id,tier,content,normalized_result,report_file_hash,result_hash,report_hash,confirmed_at,created_at) VALUES (?,?,?,?,?,'LEVEL_1_5','{}','{}',?,?,?,?,?)",
                report, commission, contract, milestone, inspector, "a".repeat(64), "b".repeat(64), "c".repeat(64), now, now);
        var throughV6 = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var d62 = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).target("7").load();
        assertThat(d62.migrate().migrationsExecuted).isEqualTo(1);
        d62.validate();
        assertThat(jdbc.queryForObject("SELECT report_file_id IS NULL FROM inspection_report WHERE report_id=?", Boolean.class, report)).isTrue();
        assertThat(jdbc.queryForObject("SELECT report_hash FROM inspection_report WHERE report_id=?", String.class, report)).isEqualTo("c".repeat(64));
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6') ORDER BY installed_rank"))
                .isEqualTo(throughV6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_report_file", Integer.class)).isZero();
        var v8 = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).target("8").load();
        assertThat(v8.migrate().migrationsExecuted).isEqualTo(1);
        v8.validate();
    }
}
