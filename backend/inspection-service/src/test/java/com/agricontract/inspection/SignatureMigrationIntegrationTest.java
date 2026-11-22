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
        var flyway = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5') ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inspector_signature", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inspector_signature' AND COLUMN_NAME='report_id'", String.class))
                .isEqualTo("ascii_bin");
    }
}
