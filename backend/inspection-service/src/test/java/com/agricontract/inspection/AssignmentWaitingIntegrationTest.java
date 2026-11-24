package com.agricontract.inspection;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.outbox.OutboxPoller;
import com.agricontract.eventing.outbox.OutboxWriter;
import com.agricontract.inspection.application.exception.EligibilityUnavailableException;
import com.agricontract.inspection.application.dto.Level15AssignmentRequestedCommand;
import com.agricontract.inspection.application.port.AssignmentAlerts.Stage;
import com.agricontract.inspection.application.port.InspectorEligibilityPort;
import com.agricontract.inspection.application.port.AuthenticatedInspectorPort;
import com.agricontract.inspection.application.usecase.InspectionAuthorization;
import com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException;
import com.agricontract.inspection.domain.model.InspectorEligibility;
import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.application.usecase.ManageLevel15Assignments;
import com.agricontract.inspection.domain.model.WaitingAssignment;
import com.agricontract.inspection.domain.model.WaitingAssignment.Key;
import com.agricontract.inspection.domain.model.WaitingAssignment.Reason;
import com.agricontract.inspection.domain.repository.AssignmentLifecycleRepository;
import com.agricontract.inspection.infrastructure.config.WaitingNotificationConfiguration;
import com.agricontract.inspection.infrastructure.messaging.Level15RequestedConsumer;
import com.agricontract.inspection.infrastructure.messaging.OutboxAssignmentAlerts;
import com.agricontract.inspection.infrastructure.client.InternalUserEligibilityClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.springframework.web.client.RestClient;
import com.agricontract.inspection.infrastructure.persistence.JdbcAssignmentWaitingRepository;
import com.agricontract.inspection.infrastructure.scheduling.AssignmentWaitingJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"SERVICE_INTERNAL_SECRET=test-service", "GATEWAY_INTERNAL_SECRET=test-gateway",
        "inspection.assignment.scheduler-enabled=false", "inspection.dlq-monitor.enabled=false",
        "agricontract.eventing.outbox.enabled=false", "VIETNAM_HOLIDAYS=2026-10-26"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssignmentWaitingIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42").withDatabaseName("inspection_db");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.rabbitmq.host", RABBIT::getHost);
        p.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        p.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        p.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ManageLevel15Assignments lifecycle;
    @Autowired AssignmentLifecycleRepository assignments;
    @Autowired Level15RequestedConsumer consumer;
    @Autowired EventEnvelopeCodec codec;
    @Autowired TransactionRunner transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired VietnamBusinessDays calendar;
    @SpyBean JdbcAssignmentWaitingRepository waiting;
    @SpyBean OutboxAssignmentAlerts alerts;
    @SpyBean OutboxWriter outbox;
    @MockBean InspectorEligibilityPort inspectors;
    @MockBean AuthenticatedInspectorPort identity;
    @Autowired InspectionAuthorization authorization;
    @MockBean RandomGenerator random;
    @MockBean Clock clock;
    static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    final String contract = UUID.randomUUID().toString(), milestone = UUID.randomUUID().toString();
    final String actor = UUID.randomUUID().toString(), a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
    final Key key = new Key(contract, milestone);

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM level1_5_assignment_waiting");
        jdbc.update("DELETE FROM inspection_report");
        jdbc.update("DELETE FROM level1_5_inspection_assignment");
        jdbc.update("DELETE FROM level1_5_assignment_lock");
        when(clock.instant()).thenReturn(NOW);
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of());
        when(inspectors.loadInspectorEmail(any())).thenReturn("inspector@example.com");
    }

    EventEnvelope event() {
        return new EventEnvelope(UUID.randomUUID(), "inspection.level1_5_requested", 1, NOW,
                "contract-service", contract, UUID.randomUUID().toString(), null, null,
                Map.of("contractId", contract, "milestoneId", milestone, "reason", "DISPUTE_ROUTED",
                        "reportDueBusinessDays", 2, "initiatedByActorId", actor, "requestedAt", NOW.toString()));
    }
    EventEnvelope cancellation(Instant at) {
        return new EventEnvelope(UUID.randomUUID(), "inspection.level1_5_cancelled", 1, NOW,
                "contract-service", contract, UUID.randomUUID().toString(), null, null,
                Map.of("contractId", contract, "milestoneId", milestone, "reason", "DISPUTE_SETTLED", "cancelledAt", at.toString()));
    }
    @Test void cancellationRemovesWaitingAndDeduplicates() {
        deliver(event());
        var before = pending();
        var cancel = cancellation(NOW.plusSeconds(1));
        deliver(cancel); deliver(cancel);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(count("level1_5_inspection_assignment")).isZero();
        assertThat(commands(Stage.CANCELLED)).isEqualTo(1);
        assertThat(events("notification.level1_5_inspector_assignment_requested")).isZero();
        assertThat(command(Stage.CANCELLED).payload()).containsEntry("reason", before.reason().name())
                .containsEntry("waitingSince", before.waitingSince().toString()).doesNotContainKey("recipients");
    }
    @Test void cancellationEndsActiveAssignmentAndDeduplicates() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var assignment = assignments.active(contract, milestone).orElseThrow();
        var at = NOW.plusSeconds(5);
        var cancel = cancellation(at);
        deliver(cancel); deliver(cancel);
        assertThat(assignments.active(contract, milestone)).isEmpty();
        assertThat(assignments.find(assignment.commissionId()).orElseThrow().endedAt()).isEqualTo(at);
        assertThat(events("notification.level1_5_inspector_assignment_requested")).isEqualTo(2);
        String json = jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type='notification.level1_5_inspector_assignment_requested' AND JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.stage'))='CANCELLED'", String.class);
        var payload = codec.parse(json.getBytes(StandardCharsets.UTF_8)).payload();
        assertThat(payload.get("recipients")).isEqualTo(List.of(Map.of("userId", a, "email", "inspector@example.com", "role", "INSPECTOR")));
    }
    @Test void cancellationUsesPersistedDeadlineAfterConfigurationChange() {
        var target = org.springframework.test.util.AopTestUtils.<ManageLevel15Assignments>getUltimateTargetObject(lifecycle);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "freeCancelHours", 4L);
        try {
            when(clock.instant()).thenReturn(NOW.plusNanos(123456000));
            when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
            deliver(event());
            var before = assignments.active(contract, milestone).orElseThrow();
            var stored = assignments.find(before.commissionId()).orElseThrow();
            assertThat(stored.freeCancelUntil()).isEqualTo(before.assignedAt().plus(Duration.ofHours(4)));
            var assigned = codec.parse(jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type='inspection.level1_5_assigned'", String.class).getBytes(StandardCharsets.UTF_8));
            var mail = codec.parse(jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type='notification.level1_5_inspector_assignment_requested'", String.class).getBytes(StandardCharsets.UTF_8));
            assertThat(assigned.payload().get("freeCancelUntil")).isEqualTo(stored.freeCancelUntil().toString());
            assertThat(mail.payload().get("freeCancelUntil")).isEqualTo(stored.freeCancelUntil().toString());
            org.springframework.test.util.ReflectionTestUtils.setField(target, "freeCancelHours", 1L);
            deliver(cancellation(NOW.plusSeconds(1)));
            var cancelled = codec.parse(jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type='notification.level1_5_inspector_assignment_requested' AND JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.stage'))='CANCELLED'", String.class).getBytes(StandardCharsets.UTF_8));
            var reloaded = assignments.find(before.commissionId()).orElseThrow();
            assertThat(cancelled.payload().get("freeCancelUntil")).isEqualTo(stored.freeCancelUntil().toString());
            assertThat(reloaded.freeCancelUntil()).isEqualTo(stored.freeCancelUntil());
            assertThat(reloaded.reportDueAt()).isEqualTo(before.reportDueAt());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(target, "freeCancelHours", 4L);
        }
    }
    @Test void historicalAssignmentWithoutDeadlineFailsClosed() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var assignment = assignments.active(contract, milestone).orElseThrow();
        jdbc.update("UPDATE level1_5_inspection_assignment SET free_cancel_until=NULL WHERE commission_id=?", assignment.commissionId());
        var cancel = cancellation(NOW.plusSeconds(1));
        assertThatThrownBy(() -> deliver(cancel)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing stored free cancellation deadline");
        assertThat(assignments.active(contract, milestone)).isPresent();
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(events("notification.level1_5_inspector_assignment_requested")).isEqualTo(1);
    }
    @Test void failedCancellationRollsBackReceiptAndCanRetry() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var assignment = assignments.active(contract, milestone).orElseThrow();
        var cancel = cancellation(NOW.plusSeconds(1));
        when(inspectors.loadInspectorEmail(a)).thenThrow(new EligibilityUnavailableException()).thenReturn("inspector@example.com");
        assertThatThrownBy(() -> deliver(cancel)).isInstanceOf(EligibilityUnavailableException.class);
        assertThat(assignments.active(contract, milestone)).isPresent();
        assertThat(count("processed_event")).isEqualTo(1);
        deliver(cancel);
        assertThat(assignments.find(assignment.commissionId()).orElseThrow().endedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(count("processed_event")).isEqualTo(2);
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outboxFailureRollsBackCancellationAndReceipt(boolean active) {
        when(inspectors.loadEligibleInspectors()).thenReturn(active ? List.of(a) : List.of());
        deliver(event());
        var cancel = cancellation(NOW.plusSeconds(1));
        var writer = org.springframework.test.util.AopTestUtils.<OutboxWriter>getUltimateTargetObject(outbox);
        doAnswer(call -> {
            EventEnvelope envelope = call.getArgument(0);
            if ("CANCELLED".equals(envelope.payload().get("stage"))) throw new IllegalStateException("injected");
            return call.callRealMethod();
        }).when(writer).append(any());
        assertThatThrownBy(() -> deliver(cancel)).isInstanceOf(IllegalStateException.class);
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(active ? 0 : 1);
        assertThat(assignments.active(contract, milestone).isPresent()).isEqualTo(active);
        doCallRealMethod().when(writer).append(any());
        deliver(cancel);
        assertThat(count("processed_event")).isEqualTo(2);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(assignments.active(contract, milestone)).isEmpty();
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationOutboxValidatesFrozenSchema(boolean active) throws Exception {
        when(inspectors.loadEligibleInspectors()).thenReturn(active ? List.of(a) : List.of());
        deliver(event()); deliver(cancellation(NOW.plusSeconds(1)));
        String type = active ? "notification.level1_5_inspector_assignment_requested" : "notification.level1_5_assignment_waiting_requested";
        String definition = active ? "level15InspectorAssignmentRequested" : "level15AssignmentWaitingRequested";
        String json = jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type=? AND JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.stage'))='CANCELLED'", String.class, type);
        var root = (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(Path.of(System.getProperty("contracts.directory"), "events", "notification-commands.yaml").toFile());
        root.remove("oneOf"); root.put("$ref", "#/$defs/" + definition);
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root,
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        assertThat(schema.validate(new ObjectMapper().readTree(json))).isEmpty();
    }
    @Test void cancellationWaitsForRetryAndEndsItsAssignment() throws Exception {
        deliver(event());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(inspectors.loadEligibleInspectors()).thenAnswer(call -> { entered.countDown(); await(release); return List.of(a); });
        var cancel = cancellation(NOW.plusSeconds(1));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var retry = pool.submit(() -> lifecycle.retryWaiting(key));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var cancelled = pool.submit(() -> deliver(cancel));
                release.countDown();
                retry.get(15, TimeUnit.SECONDS);
                cancelled.get(15, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(assignments.active(contract, milestone)).isEmpty();
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
        assertThat(events("notification.level1_5_inspector_assignment_requested")).isEqualTo(2);
    }
    @Test void concurrentDuplicateCancellationHasOneEffect() throws Exception {
        deliver(event());
        var cancel = cancellation(NOW.plusSeconds(1));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { await(start); deliver(cancel); });
            var second = pool.submit(() -> { await(start); deliver(cancel); });
            start.countDown();
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(commands(Stage.CANCELLED)).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(2);
    }
    @Test void cancellationAfterTimeoutRepickEndsNewAssignment() throws Exception {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var old = assignments.active(contract, milestone).orElseThrow();
        when(clock.instant()).thenReturn(old.reportDueAt());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(inspectors.loadEligibleInspectors()).thenAnswer(call -> { entered.countDown(); await(release); return List.of(b); });
        var cancel = cancellation(old.reportDueAt().plusSeconds(1));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var timeout = pool.submit(() -> lifecycle.timeout(old.commissionId()));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var cancelled = pool.submit(() -> deliver(cancel));
                release.countDown();
                timeout.get(15, TimeUnit.SECONDS); cancelled.get(15, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(2);
        assertThat(assignments.active(contract, milestone)).isEmpty();
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.stage'))='CANCELLED'", Integer.class)).isEqualTo(1);
    }
    @Test void retryAfterCancellationDoesNotRecreateWaiting() {
        deliver(event());
        deliver(cancellation(NOW.plusSeconds(1)));
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        lifecycle.retryWaiting(key);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(count("level1_5_inspection_assignment")).isZero();
        assertThat(commands(Stage.CANCELLED)).isEqualTo(1);
    }
    @Test void cancellationBeforeAssignmentIsRejectedWithoutChangingHistory() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var cancel = cancellation(NOW.minusSeconds(1));
        assertThatThrownBy(() -> deliver(cancel)).isInstanceOf(IllegalStateException.class);
        assertThat(assignments.active(contract, milestone)).isPresent();
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(events("notification.level1_5_inspector_assignment_requested")).isEqualTo(1);
    }
    @Test void cancellationWithNothingOpenIsNoOp() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OutboxAssignmentAlerts.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        var cancel = cancellation(NOW);
        try {
            deliver(cancel); deliver(cancel);
        } finally {
            logger.detachAppender(captured); captured.stop();
        }
        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
        assertThat(captured.list.getFirst().getFormattedMessage()).contains("inspection_level15_cancel_noop",
                cancel.eventId().toString(), contract, milestone);
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(count("level1_5_inspection_assignment")).isZero();
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(count("event_outbox")).isZero();
    }
    @Test void rolledBackNoopDoesNotLogOrClaimReceipt() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OutboxAssignmentAlerts.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        try {
            var cancel = cancellation(NOW);
            assertThatThrownBy(() -> transactions.inReadCommittedTransaction(() -> {
                deliver(cancel);
                throw new IllegalStateException("rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("rollback");
            assertThat(captured.list).isEmpty();
            assertThat(count("processed_event")).isZero();
            deliver(cancel);
            assertThat(captured.list).hasSize(1);
        } finally {
            logger.detachAppender(captured); captured.stop();
        }
    }
    @Test void cancellationRejectsAuthorizationAndPreservesEndedHistory() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        when(identity.requireInspectorId()).thenReturn(a);
        when(inspectors.load(a)).thenReturn(new InspectorEligibility(a, "INSPECTOR", "VERIFIED", null));
        deliver(event());
        var assignment = assignments.active(contract, milestone).orElseThrow();
        assertThat(authorization.requireMilestone(contract, milestone).commissionId()).isEqualTo(assignment.commissionId());
        deliver(cancellation(NOW.plusSeconds(1)));
        assertThatThrownBy(() -> authorization.requireMilestone(contract, milestone))
                .isInstanceOf(InspectionAssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> authorization.requireCommission(assignment.commissionId()))
                .isInstanceOf(InspectionAssignmentAccessDeniedException.class);
        int eventsBefore = count("event_outbox");
        deliver(cancellation(NOW.plusSeconds(2)));
        assertThat(count("event_outbox")).isEqualTo(eventsBefore);
        assertThat(assignments.find(assignment.commissionId()).orElseThrow().endedAt()).isEqualTo(NOW.plusSeconds(1));
    }
    @Test void cancellationAfterConfirmedCloseIsNoOp() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var assignment = assignments.active(contract, milestone).orElseThrow();
        String reportId = UUID.randomUUID().toString();
        transactions.inTransaction(() -> {
            jdbc.update("""
                    INSERT INTO inspection_report (report_id,l15_commission_id,contract_id,milestone_id,inspector_id,
                    tier,status,content,normalized_result,report_file_hash,result_hash,report_hash,confirmed_at)
                    VALUES (?,?,?,?,?,'LEVEL_1_5','CONFIRMED','{}','{}',?,?,?,?)
                    """, reportId, assignment.commissionId(), contract, milestone, a,
                    "a".repeat(64), "b".repeat(64), "c".repeat(64), java.sql.Timestamp.from(NOW));
            lifecycle.closeConfirmed(contract, milestone, assignment.commissionId(), a, NOW);
        });
        int eventsBefore = count("event_outbox");
        deliver(cancellation(NOW.plusSeconds(1)));
        assertThat(assignments.find(assignment.commissionId()).orElseThrow().endedAt()).isEqualTo(NOW);
        assertThat(count("event_outbox")).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM inspection_report WHERE report_id=?", String.class, reportId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT report_hash FROM inspection_report WHERE report_id=?", String.class, reportId)).isEqualTo("c".repeat(64));
    }
    @Test void cancellationWaitsForConfirmedCloseAndDoesNotOverwriteIt() throws Exception {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var assignment = assignments.active(contract, milestone).orElseThrow();
        var closed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var confirm = pool.submit(() -> transactions.inTransaction(() -> {
                lifecycle.closeConfirmed(contract, milestone, assignment.commissionId(), a, NOW);
                closed.countDown(); await(release);
            }));
            try {
                assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
                var cancel = pool.submit(() -> { started.countDown(); deliver(cancellation(NOW.plusSeconds(1))); });
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                confirm.get(15, TimeUnit.SECONDS); cancel.get(15, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(assignments.find(assignment.commissionId()).orElseThrow().endedAt()).isEqualTo(NOW);
        assertThat(events("notification.level1_5_inspector_assignment_requested")).isEqualTo(1);
    }
    @Test void confirmedCloseAfterCancellationFails() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var assignment = assignments.active(contract, milestone).orElseThrow();
        deliver(cancellation(NOW.plusSeconds(1)));
        assertThatThrownBy(() -> transactions.inTransaction(() -> lifecycle.closeConfirmed(contract, milestone, assignment.commissionId(), a, NOW)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(assignments.find(assignment.commissionId()).orElseThrow().endedAt()).isEqualTo(NOW.plusSeconds(1));
    }
    void deliver(EventEnvelope event) {
        consumer.accept(new Message(codec.toJson(event).getBytes(StandardCharsets.UTF_8)));
    }
    WaitingAssignment pending() { return transactions.inTransaction(() -> waiting.lockSkippingLocked(key).orElseThrow()); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    int commands(Stage stage) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE event_type='notification.level1_5_assignment_waiting_requested' AND JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.stage'))=?",
                Integer.class, stage.name());
    }
    EventEnvelope command(Stage stage) {
        String json = jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type='notification.level1_5_assignment_waiting_requested' AND JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.stage'))=?",
                String.class, stage.name());
        return codec.parse(json.getBytes(StandardCharsets.UTF_8));
    }
    // Stub the spy target, not its MANDATORY transaction proxy. Production calls still pass through the proxy.
    OutboxAssignmentAlerts alertTarget() { return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(alerts); }
    JdbcAssignmentWaitingRepository waitingTarget() { return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(waiting); }
    AssignmentWaitingJob job() { return new AssignmentWaitingJob(waiting, lifecycle); }
    int events(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE event_type=?", Integer.class, type);
    }
    void assertAssignmentPair(int count) {
        assertThat(events("inspection.level1_5_assigned")).isEqualTo(count);
        assertThat(events("notification.level1_5_inspector_assignment_requested")).isEqualTo(count);
    }

    @Test void firstPickAndTimeoutWriteFreshAssignmentPairs() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-23T23:30:00Z"));
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a, b));
        var request = event();
        deliver(request);
        var first = assignments.active(contract, milestone).orElseThrow();
        assertAssignmentPair(1);
        var assigned = codec.parse(jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type='inspection.level1_5_assigned'", String.class).getBytes(StandardCharsets.UTF_8));
        var notification = codec.parse(jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type='notification.level1_5_inspector_assignment_requested'", String.class).getBytes(StandardCharsets.UTF_8));
        assertThat(assigned.payload()).doesNotContainKeys("inspectorId", "email", "recipients");
        assertThat(assigned.payload().get("assignedAt")).isEqualTo(first.assignedAt().toString());
        assertThat(assigned.payload().get("freeCancelUntil")).isEqualTo(first.assignedAt().plus(Duration.ofHours(4)).toString());
        assertThat(notification.payload().get("recipients")).isEqualTo(List.of(Map.of("userId", a, "email", "inspector@example.com", "role", "INSPECTOR")));
        assertThat(notification.causationId()).isEqualTo(request.eventId().toString());
        when(clock.instant()).thenReturn(first.reportDueAt());
        lifecycle.timeout(first.commissionId());
        assertAssignmentPair(2);
        var second = assignments.active(contract, milestone).orElseThrow();
        assertThat(second.inspectorId()).isEqualTo(b);
        assertThat(second.freeCancelUntil()).isEqualTo(second.assignedAt().plus(Duration.ofHours(4)));
        assertThat(assignments.find(first.commissionId()).orElseThrow().freeCancelUntil()).isEqualTo(first.freeCancelUntil());
        assertThat(second.freeCancelUntil()).isNotEqualTo(first.freeCancelUntil());
    }

    @Test void waitingRetryPublishesPairAlongsideOperatorCommand() {
        deliver(event());
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        job().scan();
        assertAssignmentPair(1);
        var assigned = assignments.active(contract, milestone).orElseThrow();
        assertThat(assignments.find(assigned.commissionId()).orElseThrow().freeCancelUntil())
                .isEqualTo(assigned.assignedAt().plus(Duration.ofHours(4)));
        assertThat(commands(Stage.ASSIGNED)).isEqualTo(1);
        assertThat(count("level1_5_assignment_waiting")).isZero();
    }

    @Test void brokerRoutesBothOutboxEventsWhenContractConsumerIsBound() {
        var admin = new RabbitAdmin(connectionFactory);
        var queue = new Queue("test.contract-assigned." + UUID.randomUUID(), false, true, true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(WaitingNotificationConfiguration.EXCHANGE))
                .with("inspection.level1_5_assigned"));
        try {
            when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
            deliver(event());
            var rabbit = new RabbitTemplate(connectionFactory);
            rabbit.setMandatory(true); rabbit.setReturnsCallback(returned -> {});
            new OutboxPoller(jdbc, rabbit, new TransactionTemplate(manager), WaitingNotificationConfiguration.EXCHANGE,
                    100, Duration.ofSeconds(5)).poll();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE status='PUBLISHED'", Integer.class)).isEqualTo(2);
            assertThat(codec.parse(rabbit.receive(queue.getName(), 5000).getBody()).eventType()).isEqualTo("inspection.level1_5_assigned");
            assertThat(codec.parse(rabbit.receive(WaitingNotificationConfiguration.INSPECTOR_QUEUE, 5000).getBody()).eventType())
                    .isEqualTo("notification.level1_5_inspector_assignment_requested");
        } finally {
            admin.deleteQueue(queue.getName());
        }
    }

    @Test void actualAssignmentEnvelopesValidateFrozenSchemas() throws Exception {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var yaml = new ObjectMapper(new YAMLFactory());
        var json = new ObjectMapper();
        var directory = Path.of(System.getProperty("contracts.directory"), "events");
        var notification = (ObjectNode) yaml.readTree(directory.resolve("notification-commands.yaml").toFile());
        notification.remove("oneOf");
        notification.put("$ref", "#/$defs/level15InspectorAssignmentRequested");
        var golden = (ObjectNode) yaml.readTree(directory.resolve("golden-flow-events.yaml").toFile());
        var envelopeSchema = (ObjectNode) yaml.readTree(directory.resolve("event-envelope.yaml").toFile());
        ((ObjectNode) golden.path("$defs")).set("inspectionEnvelope", envelopeSchema);
        ((ObjectNode) golden.path("$defs")).set("subject", envelopeSchema.path("$defs").path("subject"));
        ((ObjectNode) golden.path("$defs").path("inspectionLevel15Assigned").path("allOf").get(0))
                .put("$ref", "#/$defs/inspectionEnvelope");
        golden.remove("oneOf");
        golden.put("$ref", "#/$defs/inspectionLevel15Assigned");
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        var options = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        for (var entry : Map.of("inspection.level1_5_assigned", golden,
                "notification.level1_5_inspector_assignment_requested", notification).entrySet()) {
            var actual = json.readTree(jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_type=?", String.class, entry.getKey()));
            assertThat(factory.getSchema(entry.getValue(), options).validate(actual)).isEmpty();
        }
    }

    @Test void emailFailureRollsBackAndRedeliverySucceedsOnce() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        when(inspectors.loadInspectorEmail(a)).thenThrow(new EligibilityUnavailableException()).thenReturn("inspector@example.com");
        var request = event();
        assertThatThrownBy(() -> deliver(request)).isInstanceOf(EligibilityUnavailableException.class);
        assertEmptyState();
        deliver(request); deliver(request);
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
        assertAssignmentPair(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.Timeout(12)
    void stalledRealUserInfoBodyRollsBackAssignment(boolean partial) throws Exception {
        var gate = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var stream = exchange.getResponseBody()) {
                if (partial) { stream.write("{\"userId\":".getBytes(StandardCharsets.UTF_8)); stream.flush(); }
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        var client = new InternalUserEligibilityClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret", 500, 500);
        try {
            when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
            when(inspectors.loadInspectorEmail(a)).thenAnswer(call -> client.loadInspectorEmail(a));
            long started = System.nanoTime();
            assertThatThrownBy(() -> deliver(event())).isInstanceOf(EligibilityUnavailableException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3000);
            assertEmptyState();
        } finally {
            gate.countDown();
            client.close();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inspection.level1_5_assigned", "notification.level1_5_inspector_assignment_requested"})
    void outboxAppendFailureRollsBackAssignmentAndAnyEarlierEvent(String failedType) {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        var writer = org.springframework.test.util.AopTestUtils.<OutboxWriter>getUltimateTargetObject(outbox);
        doAnswer(call -> {
            EventEnvelope envelope = call.getArgument(0);
            if (envelope.eventType().equals(failedType))
                throw new IllegalStateException("injected outbox failure");
            return call.callRealMethod();
        }).when(writer).append(any());
        var request = event();
        assertThatThrownBy(() -> deliver(request)).isInstanceOf(IllegalStateException.class);
        assertEmptyState();
        doCallRealMethod().when(writer).append(any());
        deliver(request);
        assertAssignmentPair(1);
    }
    void assertEmptyState() {
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(count("level1_5_inspection_assignment")).isZero();
        assertThat(count("event_outbox")).isZero();
        assertThat(count("processed_event")).isZero();
    }

    @Test void initialEmptyPoolAndBothKindsOfDuplicatesCommitOneWaitingAndOneCommand() {
        var event = event(); deliver(event); deliver(event); deliver(event());
        assertThat(count("level1_5_inspection_assignment")).isZero();
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(1);
        assertThat(pending()).isEqualTo(new WaitingAssignment(contract, milestone, Reason.NO_ELIGIBLE,
                2, "contract-service", actor, NOW, NOW, LocalDate.of(2026, 10, 23)));
        assertThat(commands(Stage.WAITING_STARTED)).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(2);
        verify(inspectors, times(1)).loadEligibleInspectors();
        verifyNoInteractions(random);
    }

    @Test void requestedAlertPreservesSourceCorrelationAndCausation() {
        var first = event();
        deliver(first);
        var alert = command(Stage.WAITING_STARTED);
        assertThat(alert.correlationId()).isEqualTo(first.correlationId()).isNotEqualTo(contract).isNotEqualTo(milestone);
        assertThat(alert.causationId()).isEqualTo(first.eventId().toString());
        assertThat(alert.eventId()).isNotEqualTo(first.eventId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"req-abc", "   "})
    void resolvedCorrelationReachesOutboxWithSourceEventAsCause(String correlation) throws Exception {
        var original = event();
        var request = new EventEnvelope(original.eventId(), original.eventType(), original.eventVersion(),
                original.occurredAt(), original.producer(), original.aggregateId(), correlation,
                null, null, original.payload());
        deliver(request);
        var alert = command(Stage.WAITING_STARTED);
        assertThat(alert.correlationId()).isEqualTo(request.eventId().toString());
        assertThat(alert.causationId()).isEqualTo(request.eventId().toString());
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(1);
        var root = (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(
                Path.of(System.getProperty("contracts.directory"), "events", "notification-commands.yaml").toFile());
        root.remove("oneOf");
        root.put("$ref", "#/$defs/level15AssignmentWaitingRequested");
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root,
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        var envelope = new ObjectMapper().readTree(jdbc.queryForObject("SELECT envelope FROM event_outbox", String.class));
        assertThat(schema.validate(envelope)).isEmpty();
    }

    @Test void missingCorrelationReachesOutboxWithSourceEventAsCorrelationAndCause() {
        var original = event();
        var request = new EventEnvelope(original.eventId(), original.eventType(), original.eventVersion(),
                original.occurredAt(), original.producer(), original.aggregateId(), null,
                null, null, original.payload());
        deliver(request);
        var alert = command(Stage.WAITING_STARTED);
        assertThat(alert.correlationId()).isEqualTo(request.eventId().toString());
        assertThat(alert.causationId()).isEqualTo(request.eventId().toString());
    }

    @Test void differentRequestsPreserveTheirOwnMetadata() {
        var first = event();
        var otherMilestone = UUID.randomUUID().toString();
        var payload = new HashMap<>(first.payload());
        payload.put("milestoneId", otherMilestone);
        var second = new EventEnvelope(UUID.randomUUID(), first.eventType(), 1, NOW, "contract-service",
                contract, UUID.randomUUID().toString(), null, null, payload);
        deliver(first);
        deliver(second);
        for (var request : List.of(first, second)) {
            String json = jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.milestoneId'))=?",
                    String.class, request.payload().get("milestoneId"));
            var alert = codec.parse(json.getBytes(StandardCharsets.UTF_8));
            assertThat(alert.correlationId()).isEqualTo(request.correlationId());
            assertThat(alert.causationId()).isEqualTo(request.eventId().toString());
        }
    }

    @ParameterizedTest
    @CsvSource({"2026-10-23T16:59:59Z,2026-10-23", "2026-10-23T17:00:00Z,none",
            "2026-10-25T03:00:00Z,none", "2026-10-26T03:00:00Z,none",
            "2026-10-22T17:00:00Z,2026-10-23"})
    void waitingStartUsesIctBusinessDate(String instant, String expected) {
        when(clock.instant()).thenReturn(Instant.parse(instant));
        deliver(event());
        assertThat(pending().lastReminderOn()).isEqualTo(expected.equals("none") ? null : LocalDate.parse(expected));
        job().scan();
        assertThat(commands(Stage.STILL_WAITING)).isZero();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-26T17:00:00Z"));
        job().scan();
        job().scan();
        assertThat(commands(Stage.STILL_WAITING)).isEqualTo(1);
        assertThat(pending().lastReminderOn()).isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test void jobAlertsHaveIndependentRootMetadata() {
        var source = event();
        deliver(source);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-26T17:00:00Z"));
        job().scan();
        var reminder = command(Stage.STILL_WAITING);
        assertThat(UUID.fromString(reminder.correlationId())).isNotNull();
        assertThat(reminder.correlationId()).isNotEqualTo(source.correlationId()).isNotEqualTo(contract).isNotEqualTo(milestone);
        assertThat(reminder.causationId()).isNull();
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        job().scan();
        var assigned = command(Stage.ASSIGNED);
        assertThat(UUID.fromString(assigned.correlationId())).isNotNull();
        assertThat(assigned.correlationId()).isNotEqualTo(reminder.correlationId()).isNotEqualTo(contract).isNotEqualTo(milestone);
        assertThat(assigned.causationId()).isNull();
    }

    @Test void timeoutAlertHasIndependentRootMetadata() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        var source = event();
        deliver(source);
        var old = assignments.active(contract, milestone).orElseThrow();
        when(clock.instant()).thenReturn(old.reportDueAt());
        lifecycle.timeout(old.commissionId());
        var alert = command(Stage.WAITING_STARTED);
        assertThat(UUID.fromString(alert.correlationId())).isNotNull();
        assertThat(alert.correlationId()).isNotEqualTo(source.correlationId()).isNotEqualTo(contract).isNotEqualTo(milestone).isNotEqualTo(old.commissionId());
        assertThat(alert.causationId()).isNull();
    }

    @Test void activeAssignmentPreventsWaitingEvenWhenPoolBecomesEmpty() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of());
        deliver(event());
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertAssignmentPair(1);
        verify(inspectors, times(1)).loadEligibleInspectors();
    }

    @Test void waitingTimeStartsAfterEligibilityLookupCompletes() {
        Instant detected = NOW.plusSeconds(90);
        when(clock.instant()).thenReturn(NOW, detected);
        deliver(event());
        var stored = pending();
        assertThat(stored.waitingSince()).isEqualTo(detected);
        assertThat(stored.lastAttemptAt()).isEqualTo(detected);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.waitingSince')) FROM event_outbox",
                String.class)).isEqualTo(detected.toString());
    }

    @ParameterizedTest @ValueSource(strings = {"insert", "beforeCommand", "afterCommand"})
    void initialFailureRollsBackWaitingCommandAndProcessedClaimThenRedeliveryRecovers(String point) {
        var request = event();
        if (point.equals("insert")) doThrow(new IllegalStateException("injected")).when(waitingTarget()).insert(any());
        else doAnswer(call -> {
            assertThat(count("level1_5_assignment_waiting")).isEqualTo(1);
            if (point.equals("afterCommand")) call.callRealMethod();
            throw new IllegalStateException("injected");
        }).when(alertTarget()).waitingChanged(any(), eq(Stage.WAITING_STARTED), any(), any());
        assertThatThrownBy(() -> deliver(request)).isInstanceOf(point.equals("insert")
                ? org.springframework.dao.InvalidDataAccessApiUsageException.class : IllegalStateException.class)
                .hasMessage("injected");
        assertEmptyState();
        doCallRealMethod().when(waitingTarget()).insert(any());
        doCallRealMethod().when(alertTarget()).waitingChanged(any(), any(), any(), any());
        deliver(request);
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(1);
        assertThat(commands(Stage.WAITING_STARTED)).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentInitialDuplicatesCannotDuplicateWaitingOrNotification(boolean sameEvent) throws Exception {
        var first = event(); var second = sameEvent ? first : event();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { await(start); deliver(first); });
            var two = pool.submit(() -> { await(start); deliver(second); });
            start.countDown(); one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
        }
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(1);
        assertThat(commands(Stage.WAITING_STARTED)).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(sameEvent ? 1 : 2);
        verify(inspectors, times(1)).loadEligibleInspectors();
    }

    @Test void differentMilestonesCanEnterWaitingConcurrently() throws Exception {
        String otherContract = UUID.randomUUID().toString();
        String otherMilestone = UUID.randomUUID().toString();
        var reachedReservation = new CountDownLatch(2);
        doAnswer(call -> {
            reachedReservation.countDown();
            await(reachedReservation);
            return call.callRealMethod();
        }).when(waitingTarget()).tryReserve(any());
        var first = new Level15AssignmentRequestedCommand(contract, milestone, 2, actor, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var second = new Level15AssignmentRequestedCommand(otherContract, otherMilestone, 2, actor, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> lifecycle.requested(first));
            var two = pool.submit(() -> lifecycle.requested(second));
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(2);
        assertThat(count("level1_5_inspection_assignment")).isZero();
        assertThat(commands(Stage.WAITING_STARTED)).isEqualTo(2);
        for (String milestoneId : List.of(milestone, otherMilestone)) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_assignment_waiting WHERE milestone_id=?",
                    Integer.class, milestoneId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.milestoneId'))=?",
                    Integer.class, milestoneId)).isEqualTo(1);
        }
    }

    @Test void differentMilestonesWithEligibleInspectorsCanBeAssignedConcurrently() throws Exception {
        String otherContract = UUID.randomUUID().toString();
        String otherMilestone = UUID.randomUUID().toString();
        var bothReady = new CountDownLatch(2);
        when(inspectors.loadEligibleInspectors()).thenAnswer(call -> {
            bothReady.countDown();
            await(bothReady);
            return List.of(a);
        });
        var first = event();
        var second = new EventEnvelope(UUID.randomUUID(), "inspection.level1_5_requested", 1, NOW,
                "contract-service", otherContract, UUID.randomUUID().toString(), null, null,
                Map.of("contractId", otherContract, "milestoneId", otherMilestone, "reason", "DISPUTE_ROUTED",
                        "reportDueBusinessDays", 2, "initiatedByActorId", actor, "requestedAt", NOW.toString()));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> deliver(first));
            var two = pool.submit(() -> deliver(second));
            awaitAssignment(one);
            awaitAssignment(two);
        }
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(2);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertAssignmentPair(2);
        assertThat(count("processed_event")).isEqualTo(2);
        for (String milestoneId : List.of(milestone, otherMilestone)) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_inspection_assignment WHERE milestone_id=? AND ended_at IS NULL",
                    Integer.class, milestoneId)).isEqualTo(1);
        }
    }

    private static void awaitAssignment(Future<?> result) throws Exception {
        try {
            result.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure;
            while (cause != null && !(cause instanceof SQLException)) {
                cause = cause.getCause();
            }
            if (cause instanceof SQLException sql) {
                throw new AssertionError("Assignment failed with MySQL error " + sql.getErrorCode()
                        + " and SQLState " + sql.getSQLState(), failure);
            }
            throw failure;
        }
    }

    @Test void earlierReadStillSeesCommittedWaitingUnderReadCommitted() throws Exception {
        var request = new Level15AssignmentRequestedCommand(contract, milestone, 2, actor, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        try (var pool = Executors.newSingleThreadExecutor()) {
            transactions.inReadCommittedTransaction(() -> {
                assertThat(count("level1_5_assignment_waiting")).isZero();
                try {
                    pool.submit(() -> lifecycle.requested(request)).get(15, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                lifecycle.requested(request);
            });
        }
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(1);
        assertThat(commands(Stage.WAITING_STARTED)).isEqualTo(1);
        verify(inspectors, times(1)).loadEligibleInspectors();
    }

    @Test void repeatableReadCallerIsRejectedBeforeAssignmentWork() {
        var request = new Level15AssignmentRequestedCommand(contract, milestone, 2, actor, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var repeatableRead = new TransactionTemplate(manager);
        repeatableRead.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatableRead.executeWithoutResult(status -> {
            assertThat(count("level1_5_assignment_waiting")).isZero();
            lifecycle.requested(request);
        })).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class)
                .hasMessageContaining("READ_COMMITTED");
        assertEmptyState();
        verifyNoInteractions(inspectors, random, alerts);
    }

    @Test void incompatibleConsumerTransactionDoesNotClaimEvent() {
        var repeatableRead = new TransactionTemplate(manager);
        repeatableRead.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatableRead.executeWithoutResult(status -> deliver(event())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class)
                .hasMessageContaining("READ_COMMITTED");
        assertEmptyState();
        verifyNoInteractions(inspectors, random, alerts);
    }

    @Test void earlierReadSeesNewActiveAssignmentUnderReadCommitted() throws Exception {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        try (var pool = Executors.newSingleThreadExecutor()) {
            transactions.inReadCommittedTransaction(() -> {
                assertThat(count("level1_5_inspection_assignment")).isZero();
                try {
                    pool.submit(() -> deliver(event())).get(15, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                deliver(event());
            });
        }
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertAssignmentPair(1);
        verify(inspectors, times(1)).loadEligibleInspectors();
    }

    @Test void timeoutAndWaitingScanDoNotDoubleAssign() throws Exception {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var old = assignments.active(contract, milestone).orElseThrow();
        when(clock.instant()).thenReturn(old.reportDueAt());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(inspectors.loadEligibleInspectors()).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return List.of();
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var timeout = pool.submit(() -> lifecycle.timeout(old.commissionId()));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                job().scan();
                assertThat(count("level1_5_assignment_waiting")).isZero();
            } finally {
                release.countDown();
            }
            timeout.get(15, TimeUnit.SECONDS);
        }
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(b));
        job().scan();
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(2);
        assertThat(assignments.active(contract, milestone).orElseThrow().inspectorId()).isEqualTo(b);
        assertThat(commands(Stage.WAITING_STARTED)).isEqualTo(1);
        assertThat(commands(Stage.ASSIGNED)).isEqualTo(1);
    }

    @Test void reportConfirmationAndTimeoutSerializeOnMilestone() throws Exception {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var old = assignments.active(contract, milestone).orElseThrow();
        when(clock.instant()).thenReturn(old.reportDueAt());
        Instant confirmed = old.reportDueAt().minusSeconds(1);
        var closed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var timeoutStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var report = pool.submit(() -> transactions.inTransaction(() -> {
                lifecycle.closeConfirmed(contract, milestone, old.commissionId(), a, confirmed);
                closed.countDown();
                await(release);
            }));
            try {
                assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
                var timeout = pool.submit(() -> {
                    timeoutStarted.countDown();
                    lifecycle.timeout(old.commissionId());
                });
                assertThat(timeoutStarted.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                timeout.get(15, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            report.get(15, TimeUnit.SECONDS);
        }
        assertThat(assignments.find(old.commissionId()).orElseThrow().endedAt()).isEqualTo(confirmed);
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertAssignmentPair(1);
    }

    @Test void overdueAllExcludedPreservesHistoryContextAndRetriesWithFreshDeadline() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        var old = assignments.active(contract, milestone).orElseThrow();
        // Assert provenance is read from persistence, rather than replaced with a constant on retry.
        jdbc.update("UPDATE level1_5_inspection_assignment SET assigned_by_service='original-service'");
        when(clock.instant()).thenReturn(old.reportDueAt());
        lifecycle.timeout(old.commissionId()); lifecycle.timeout(old.commissionId());
        var originalWaiting = pending();
        assertThat(originalWaiting.reason()).isEqualTo(Reason.ALL_EXCLUDED);
        assertThat(originalWaiting.assignedByService()).isEqualTo("original-service");
        assertThat(originalWaiting.reportDueBusinessDays()).isEqualTo(2);
        assertThat(originalWaiting.initiatedByActorId()).isEqualTo(actor);
        assertThat(assignments.find(old.commissionId()).orElseThrow().endedAt()).isEqualTo(old.reportDueAt());
        assertThat(commands(Stage.WAITING_STARTED)).isEqualTo(1);
        Instant retryTime = old.reportDueAt().plusSeconds(86400 * 3);
        when(clock.instant()).thenReturn(retryTime);
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a, b));
        job().scan(); job().scan();
        var assigned = assignments.active(contract, milestone).orElseThrow();
        assertThat(assigned.inspectorId()).isEqualTo(b);
        assertThat(assigned.assignedAt()).isEqualTo(retryTime);
        assertThat(assigned.reportDueAt()).isEqualTo(calendar.deadline(retryTime, 2));
        assertThat(assigned.reportDueAt()).isNotEqualTo(calendar.deadline(originalWaiting.waitingSince(), 2));
        assertThat(assigned.assignedByService()).isEqualTo("original-service");
        assertThat(assigned.initiatedByActorId()).isEqualTo(actor);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(2);
        assertThat(commands(Stage.ASSIGNED)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.waitingSince')) FROM event_outbox WHERE event_type='notification.level1_5_assignment_waiting_requested' AND JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.payload.stage'))='ASSIGNED'", String.class))
                .isEqualTo(originalWaiting.waitingSince().toString());
    }

    @Test void overdueNotificationFailureRollsBackEndingAndWaitingTogether() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event()); var old = assignments.active(contract, milestone).orElseThrow();
        when(clock.instant()).thenReturn(old.reportDueAt());
        doThrow(new IllegalStateException("outbox failed")).when(alertTarget()).waitingChanged(any(), any(), any(), any());
        assertThatThrownBy(() -> lifecycle.timeout(old.commissionId())).isInstanceOf(IllegalStateException.class);
        assertThat(assignments.active(contract, milestone).orElseThrow()).isEqualTo(old);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertAssignmentPair(1);
        doCallRealMethod().when(alertTarget()).waitingChanged(any(), any(), any(), any());
        lifecycle.timeout(old.commissionId());
        assertThat(pending().reason()).isEqualTo(Reason.ALL_EXCLUDED);
    }

    @ParameterizedTest @ValueSource(strings = {"beforeDelete", "beforeCommand", "afterCommand"})
    void retryFailureRollsBackAssignmentDeletionAndCommand(String point) {
        deliver(event()); var original = pending();
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(b));
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        if (point.equals("beforeDelete")) doAnswer(call -> {
            assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
            throw new IllegalStateException("injected");
        }).when(waitingTarget()).delete(key);
        else doAnswer(call -> {
            assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
            assertThat(count("level1_5_assignment_waiting")).isZero();
            if (point.equals("afterCommand")) call.callRealMethod();
            throw new IllegalStateException("injected");
        }).when(alertTarget()).waitingChanged(any(), eq(Stage.ASSIGNED), any(), any());
        assertThatThrownBy(() -> lifecycle.retryWaiting(key)).isInstanceOf(point.equals("beforeDelete")
                ? org.springframework.dao.InvalidDataAccessApiUsageException.class : IllegalStateException.class)
                .hasMessage("injected");
        assertThat(pending()).isEqualTo(original);
        assertThat(count("level1_5_inspection_assignment")).isZero();
        assertThat(commands(Stage.ASSIGNED)).isZero();
        doCallRealMethod().when(waitingTarget()).delete(any());
        doCallRealMethod().when(alertTarget()).waitingChanged(any(), any(), any(), any());
        job().scan();
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
        assertThat(count("level1_5_assignment_waiting")).isZero();
        assertThat(commands(Stage.ASSIGNED)).isEqualTo(1);
    }

    @Test void remindersRespectIctDateWeekendConfiguredHolidayAndDailyDeduplication() {
        deliver(event());
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        job().scan(); job().scan();
        assertThat(commands(Stage.STILL_WAITING)).isZero();
        assertThat(pending().lastReminderOn()).isEqualTo(LocalDate.of(2026, 10, 23));
        for (String instant : List.of("2026-10-23T17:00:00Z", "2026-10-25T03:00:00Z", "2026-10-26T03:00:00Z")) {
            when(clock.instant()).thenReturn(Instant.parse(instant));
            job().scan();
            assertThat(commands(Stage.STILL_WAITING)).isZero();
            assertThat(pending().lastAttemptAt()).isEqualTo(Instant.parse(instant));
        }
        // Tuesday in ICT, still Monday in UTC; Monday is configured as a holiday.
        when(clock.instant()).thenReturn(Instant.parse("2026-10-26T17:00:00Z"));
        job().scan(); job().scan();
        assertThat(commands(Stage.STILL_WAITING)).isEqualTo(1);
        assertThat(pending().lastReminderOn()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(pending().waitingSince()).isEqualTo(NOW);
        assertThat(pending().reason()).isEqualTo(Reason.NO_ELIGIBLE);
    }

    @Test void reminderFailureDoesNotConsumeDailyReminderOrAdvanceAttempt() {
        deliver(event()); var original = pending();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-26T17:00:00Z"));
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("injected"); })
                .when(alertTarget()).waitingChanged(any(), eq(Stage.STILL_WAITING), any(), any());
        job().scan();
        assertThat(pending()).isEqualTo(original);
        assertThat(commands(Stage.STILL_WAITING)).isZero();
        doCallRealMethod().when(alertTarget()).waitingChanged(any(), any(), any(), any());
        job().scan(); job().scan();
        assertThat(commands(Stage.STILL_WAITING)).isEqualTo(1);
    }

    @Test void concurrentRetriesEmitOnlyOneDailyReminder() throws Exception {
        deliver(event());
        when(clock.instant()).thenReturn(Instant.parse("2026-10-26T17:00:00Z"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(inspectors.loadEligibleInspectors()).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return List.of();
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> job().scan());
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> job().scan());
                second.get(15, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            first.get(15, TimeUnit.SECONDS);
        }
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of());
        job().scan();
        assertThat(commands(Stage.STILL_WAITING)).isEqualTo(1);
        assertThat(pending().lastReminderOn()).isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test void dependencyFailureRollsBackInitialClaimAndLeavesScheduledRowUnchanged() {
        var request = event();
        when(inspectors.loadEligibleInspectors()).thenThrow(new EligibilityUnavailableException());
        assertThatThrownBy(() -> deliver(request)).isInstanceOf(EligibilityUnavailableException.class);
        assertEmptyState();
        doReturn(List.of()).when(inspectors).loadEligibleInspectors();
        deliver(request); var original = pending();
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        when(inspectors.loadEligibleInspectors()).thenThrow(new EligibilityUnavailableException());
        job().scan();
        assertThat(pending()).isEqualTo(original);
        assertThat(count("event_outbox")).isEqualTo(1);
        assertThat(count("level1_5_inspection_assignment")).isZero();
    }

    @Test void failedFirstRowDoesNotStarveOtherRowsOrNextKeysetPage() {
        transactions.inTransaction(() -> {
            for (int i = 0; i < 101; i++) {
                String id = String.format("00000000-0000-4000-8000-%012d", i);
                assignments.lock(contract, id);
                waiting.insert(new WaitingAssignment(contract, id, Reason.NO_ELIGIBLE, 2,
                        "contract-service", actor, NOW, NOW, null));
            }
        });
        AtomicInteger attempts = new AtomicInteger();
        when(inspectors.loadEligibleInspectors()).thenAnswer(call -> {
            if (attempts.getAndIncrement() == 0) throw new EligibilityUnavailableException();
            return List.of(a);
        });
        job().scan();
        assertThat(attempts).hasValue(101);
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(1);
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(100);
        assertThat(commands(Stage.ASSIGNED)).isEqualTo(100);
    }

    @Test void concurrentWorkersUseDifferentConnectionsSkipLockedAndAssignOnce() throws Exception {
        deliver(event());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstConnection = new AtomicLong(); var secondConnection = new AtomicLong();
        when(inspectors.loadEligibleInspectors()).thenAnswer(call -> {
            firstConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            entered.countDown(); await(release); return List.of(b);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> job().scan());
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> transactions.inReadCommittedTransaction(() -> {
                    secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    // Directly prove the actual waiting row is locked by worker one, independently of the guard.
                    assertThat(waiting.lockSkippingLocked(key)).isEmpty();
                    job().scan();
                }));
                second.get(5, TimeUnit.SECONDS); // must finish while the first transaction still holds its locks
                assertThat(secondConnection.get()).isNotEqualTo(firstConnection.get());
                assertThat(count("level1_5_inspection_assignment")).isZero();
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
        assertThat(assignments.active(contract, milestone).orElseThrow().inspectorId()).isEqualTo(b);
        assertThat(commands(Stage.ASSIGNED)).isEqualTo(1);
        assertThat(count("level1_5_assignment_waiting")).isZero();
    }

    @Test void directlyLockedWaitingRowIsSkippedAndRecoveredOnNextScan() throws Exception {
        deliver(event());
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> transactions.inTransaction(() -> {
                assertThat(waiting.lockSkippingLocked(key)).isPresent();
                locked.countDown(); await(release);
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                when(inspectors.loadEligibleInspectors()).thenReturn(List.of(b));
                job().scan();
                assertThat(count("level1_5_inspection_assignment")).isZero();
                assertThat(commands(Stage.ASSIGNED)).isZero();
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
        }
        job().scan();
        assertThat(commands(Stage.ASSIGNED)).isEqualTo(1);
    }

    @Test void staleWaitingWithActiveAssignmentIsPreservedWithoutRepicking() {
        when(inspectors.loadEligibleInspectors()).thenReturn(List.of(a));
        deliver(event());
        transactions.inTransaction(() -> {
            assignments.lock(contract, milestone);
            waiting.insert(new WaitingAssignment(contract, milestone, Reason.NO_ELIGIBLE, 2,
                    "contract-service", actor, NOW, NOW, null));
        });
        job().scan(); job().scan();
        assertThat(count("level1_5_inspection_assignment")).isEqualTo(1);
        assertThat(count("level1_5_assignment_waiting")).isEqualTo(1);
        assertThat(commands(Stage.ASSIGNED)).isZero();
        verify(inspectors, times(1)).loadEligibleInspectors();
    }

    @ParameterizedTest
    @CsvSource({"NO_ELIGIBLE,WAITING_STARTED", "NO_ELIGIBLE,STILL_WAITING", "NO_ELIGIBLE,ASSIGNED",
            "ALL_EXCLUDED,WAITING_STARTED", "ALL_EXCLUDED,STILL_WAITING", "ALL_EXCLUDED,ASSIGNED"})
    void realOutboxEnvelopeValidatesAgainstFrozenCommandSchema(Reason reason, Stage stage) throws Exception {
        transactions.inTransaction(() -> alerts.waitingChanged(new WaitingAssignment(contract, milestone,
                reason, 2, "contract-service", actor, NOW, NOW, null), stage, UUID.randomUUID().toString(), null));
        var root = (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(
                Path.of(System.getProperty("contracts.directory"), "events", "notification-commands.yaml").toFile());
        root.remove("oneOf"); root.put("$ref", "#/$defs/level15AssignmentWaitingRequested");
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root,
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        var envelope = (ObjectNode) new ObjectMapper().readTree(jdbc.queryForObject("SELECT envelope FROM event_outbox", String.class));
        assertThat(schema.validate(envelope)).isEmpty();
        assertThat(envelope.path("payload").properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("contractId", "milestoneId", "reason", "waitingSince", "stage");
        assertThat(envelope.path("payload").path("reason").asText()).isEqualTo(reason.name());
        assertThat(envelope.path("payload").path("stage").asText()).isEqualTo(stage.name());
        var invalid = envelope.deepCopy(); ((ObjectNode) invalid.path("payload")).put("inspectorEmail", "private@example.test");
        assertThat(schema.validate(invalid)).isNotEmpty();
        invalid = envelope.deepCopy(); ((ObjectNode) invalid.path("payload")).put("reason", "UNKNOWN");
        assertThat(schema.validate(invalid)).isNotEmpty();
        invalid = envelope.deepCopy(); ((ObjectNode) invalid.path("payload")).put("stage", "UNKNOWN");
        assertThat(schema.validate(invalid)).isNotEmpty();
        invalid = envelope.deepCopy(); ((ObjectNode) invalid.path("payload")).remove("milestoneId");
        assertThat(schema.validate(invalid)).isNotEmpty();
    }

    @Test void predeclaredQueueReceivesOutboxWithoutNotificationService() {
        deliver(event());
        var rabbit = new RabbitTemplate(connectionFactory);
        rabbit.setMandatory(true); rabbit.setReturnsCallback(returned -> {});
        new OutboxPoller(jdbc, rabbit, new TransactionTemplate(manager), WaitingNotificationConfiguration.EXCHANGE,
                100, Duration.ofSeconds(5)).poll();
        assertThat(jdbc.queryForObject("SELECT status FROM event_outbox", String.class)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox", Integer.class)).isZero();
        var message = rabbit.receive(WaitingNotificationConfiguration.QUEUE, 5000);
        assertThat(message).isNotNull();
        assertThat(codec.parse(message.getBody()).eventType())
                .isEqualTo(OutboxAssignmentAlerts.EVENT_TYPE);
    }

    @ParameterizedTest @CsvSource({"BAD,2", "NO_ELIGIBLE,0", "ALL_EXCLUDED,31"})
    void mysqlEnforcesFrozenWaitingConstraints(String reason, int days) {
        var failure = catchThrowableOfType(() -> jdbc.update("""
                INSERT INTO level1_5_assignment_waiting
                (contract_id,milestone_id,reason,report_due_business_days,assigned_by_service,waiting_since,last_attempt_at)
                VALUES (?,?,?,?,'contract-service',NOW(6),NOW(6))
                """, contract, milestone, reason, days), org.springframework.dao.DataAccessException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class, sql -> {
            assertThat(sql.getErrorCode()).isEqualTo(3819);
            assertThat(sql.getMessage()).contains(reason.equals("BAD") ? "chk_l15_waiting_reason" : "chk_l15_waiting_due");
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
    }
}
