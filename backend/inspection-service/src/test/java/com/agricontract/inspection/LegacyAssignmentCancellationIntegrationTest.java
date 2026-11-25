package com.agricontract.inspection;

import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.inspection.application.port.InspectorEligibilityPort;
import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.application.usecase.ManageLevel15Assignments;
import com.agricontract.inspection.infrastructure.messaging.Level15RequestedConsumer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"SERVICE_INTERNAL_SECRET=test", "GATEWAY_INTERNAL_SECRET=test", "VIETNAM_HOLIDAYS=2026-01-01",
        "inspection.assignment.scheduler-enabled=false", "inspection.dlq-monitor.enabled=false",
        "agricontract.eventing.outbox.enabled=false", "spring.rabbitmq.listener.simple.auto-startup=false", "management.health.rabbit.enabled=false"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyAssignmentCancellationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42")
            .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");
    static final String CONTRACT = UUID.randomUUID().toString(), MILESTONE = UUID.randomUUID().toString(),
            COMMISSION = UUID.randomUUID().toString(), INSPECTOR = UUID.randomUUID().toString();
    static final Instant ASSIGNED = Instant.parse("2026-10-23T03:00:00.123456Z");

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).target("8").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("""
                INSERT INTO level1_5_inspection_assignment
                (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,report_due_business_days,report_due_at)
                VALUES (?,?,?,?,?,'contract-service',2,?)
                """, COMMISSION, CONTRACT, MILESTONE, INSPECTOR, Timestamp.from(ASSIGNED), Timestamp.from(ASSIGNED.plusSeconds(86400)));
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EventEnvelopeCodec codec;
    @Autowired Level15RequestedConsumer consumer;
    @Autowired ManageLevel15Assignments lifecycle;
    @Autowired TransactionRunner transactions;
    @MockBean InspectorEligibilityPort users;

    @Test void upgradedActiveAssignmentCancelsOnceWithBackfilledDeadline() throws Exception {
        var deadline = ASSIGNED.plusSeconds(4 * 3600);
        assertThat(jdbc.queryForObject("SELECT free_cancel_until FROM level1_5_inspection_assignment WHERE commission_id=?", Timestamp.class, COMMISSION).toInstant()).isEqualTo(deadline);
        when(users.loadInspectorEmail(INSPECTOR)).thenThrow(new IllegalStateException("user-service unavailable")).thenReturn("inspector@example.com");
        var cancelledAt = ASSIGNED.plusSeconds(60);
        var cancel = new EventEnvelope(UUID.randomUUID(), "inspection.level1_5_cancelled", 1, cancelledAt,
                "contract-service", CONTRACT, UUID.randomUUID().toString(), null, null,
                Map.of("contractId", CONTRACT, "milestoneId", MILESTONE, "reason", "DISPUTE_SETTLED", "cancelledAt", cancelledAt.toString()));
        var message = new Message(codec.toJson(cancel).getBytes(StandardCharsets.UTF_8), new MessageProperties());
        assertThatThrownBy(() -> consumer.accept(message)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=?", Integer.class, cancel.eventId().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_inspection_assignment WHERE ended_at IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class)).isZero();
        consumer.accept(message); consumer.accept(message);
        assertThat(jdbc.queryForObject("SELECT ended_at FROM level1_5_inspection_assignment WHERE commission_id=?", Timestamp.class, COMMISSION).toInstant()).isEqualTo(cancelledAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_inspection_assignment WHERE ended_at IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=?", Integer.class, cancel.eventId().toString())).isEqualTo(1);
        var json = jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type='notification.level1_5_inspector_assignment_requested'", String.class);
        var notice = codec.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(notice.payload()).containsEntry("freeCancelUntil", deadline.toString()).containsEntry("stage", "CANCELLED");
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readTree(java.nio.file.Path.of(System.getProperty("contracts.directory"), "events", "notification-commands.yaml").toFile());
        root.remove("oneOf"); root.put("$ref", "#/$defs/level15InspectorAssignmentRequested");
        var schema = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(root, com.networknt.schema.SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        assertThat(schema.validate(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> transactions.inTransaction(() -> lifecycle.closeConfirmed(CONTRACT, MILESTONE, COMMISSION, INSPECTOR, cancelledAt))).isInstanceOf(IllegalStateException.class);
    }
}
