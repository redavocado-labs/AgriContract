package com.agricontract.audit.infrastructure.messaging;

import com.agricontract.audit.infrastructure.config.RabbitConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The whole service on real MySQL (production migration and grants) and RabbitMQ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContractSignedFlowIntegrationTest {

    static final String UTC = "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42")
            .withDatabaseName("audit_db").withUsername("audit_migration")
            .withPassword("test-only-migration-password").withEnv("MYSQL_ROOT_HOST", "%");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final ObjectMapper JSON = new ObjectMapper();

    static {
        MYSQL.start();
        RABBIT.start();
        try {
            // Production order: Flyway as the migration account, then ops/grants.sql for the runtime account.
            Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            JdbcTemplate admin = new JdbcTemplate(
                    new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
            admin.execute("CREATE USER 'audit_runtime'@'%' IDENTIFIED BY 'test-only-runtime-password'");
            String sql = Files.readString(Path.of("ops/grants.sql")).replaceAll("(?m)--[^\\r\\n]*", "");
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) admin.execute(statement);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + UTC);
        registry.add("spring.datasource.username", () -> "audit_runtime");
        registry.add("spring.datasource.password", () -> "test-only-runtime-password");
        registry.add("spring.flyway.url", () -> MYSQL.getJdbcUrl());
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("audit.dlq-monitor.enabled", () -> "false");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void purge() {
        admin.purgeQueue(RabbitConfiguration.CONTRACT_SIGNED_QUEUE, false);
        admin.purgeQueue(RabbitConfiguration.CONTRACT_SIGNED_DLQ, false);
    }

    // ---- #255: the catalog events ----

    @Test
    void catalogEvents_fromEveryProducerExchange_chainOnTheirSubject_onceEach() throws Exception {
        Map<String, Map<String, Object>> samples = CatalogEventConsumerTest.samples();
        String contractId = (String) samples.get("milestone.seller_weighed").get("contractId");
        List<String> ids = new java.util.ArrayList<>();
        for (CatalogEvent event : CatalogEvent.ALL) {
            Map<String, Object> envelope = CatalogEventConsumerTest.envelope(event.eventType(),
                    samples.get(event.eventType()));
            ids.add((String) envelope.get("eventId"));
            sendTo(event.exchange(), envelope);
            if (event.eventType().equals("milestone.seller_weighed")) {
                sendTo(event.exchange(), envelope);
            }
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> ids.stream().allMatch(id -> count("source_event_id", id) == 1));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE source_event_id IN ("
                + String.join(",", ids.stream().map(id -> "'" + id + "'").toList()) + ")", Integer.class))
                .as("one row per event, the repeated delivery included").isEqualTo(CatalogEvent.ALL.size());
        // The contract's records link to each other in global order: each prev_hash_subject is the record before.
        List<Map<String, Object>> chain = jdbc.queryForList("SELECT record_hash, prev_hash_subject FROM audit_record"
                + " WHERE subject_type = 'CONTRACT' AND subject_id = ? ORDER BY global_seq", contractId);
        assertThat(chain).hasSizeGreaterThan(10);
        for (int i = 1; i < chain.size(); i++) {
            assertThat(chain.get(i).get("prev_hash_subject")).isEqualTo(chain.get(i - 1).get("record_hash"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE subject_type = 'SYSTEM'"
                + " AND source_type IN ('SECURITY_LOCK_TRIGGERED', 'EXTERNAL_VERIFIER_KEY_REGISTERED')"
                + " AND source_event_id IN (?, ?)", Integer.class, ids.get(ids.size() - 4), ids.get(ids.size() - 3)))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content FROM audit_record WHERE source_type = 'MILESTONE_EVENT'"
                + " AND source_event_type = 'milestone.settled' AND subject_id = ?", String.class, contractId))
                .doesNotContain("recipients").doesNotContain("@");
    }

    @Test
    void aBrokenCatalogEvent_goesToItsDlq_andWritesNothing() throws Exception {
        Map<String, Object> payload = CatalogEventConsumerTest.samples().get("remedy.finalized");
        payload.remove("remedyLegs");
        Map<String, Object> envelope = CatalogEventConsumerTest.envelope("remedy.finalized", payload);
        sendTo(CatalogEvent.CONTRACTS, envelope);

        String dlq = CatalogEvent.queue("remedy.finalized") + ".dlq";
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            Message dead = rabbit.receive(dlq, 500);
            return dead != null && JSON.readTree(dead.getBody()).get("eventId").asText().equals(envelope.get("eventId"));
        });
        assertThat(count("source_event_id", (String) envelope.get("eventId"))).isZero();
    }

    private void sendTo(String exchange, Map<String, Object> event) throws Exception {
        Message message = MessageBuilder.withBody(JSON.writeValueAsBytes(event))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbit.send(exchange, (String) event.get("eventType"), message);
    }

    @Test
    void sameEventDeliveredTwiceWritesOneRow() throws Exception {
        Map<String, Object> event = ContractSignedConsumerTest.validEvent();
        String eventId = (String) event.get("eventId");
        send(event);
        send(event);
        // A different event as a barrier: once it is stored, the two deliveries before it have been handled.
        Map<String, Object> barrier = ContractSignedConsumerTest.validEvent();
        send(barrier);
        awaitRows((String) barrier.get("eventId"), 1);

        assertThat(count("source_event_id", eventId)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT subject_type, subject_id, source_type, source_event_type, source_hash FROM audit_record "
                        + "WHERE source_event_id = ?", eventId);
        assertThat(row).containsEntry("subject_type", "CONTRACT").containsEntry("source_type", "CONTRACT_SIGNED")
                .containsEntry("source_event_type", "contract.signed")
                .containsEntry("subject_id", ContractSignedConsumerTest.payload(event).get("contractId"))
                .containsEntry("source_hash", ContractSignedConsumerTest.payload(event).get("signedContentHash"));
    }

    @Test
    void twoDifferentEventsForTheSameContractWriteTwoRows() throws Exception {
        Map<String, Object> first = ContractSignedConsumerTest.validEvent();
        Map<String, Object> second = ContractSignedConsumerTest.validEvent();
        String contractId = (String) ContractSignedConsumerTest.payload(first).get("contractId");
        ContractSignedConsumerTest.payload(second).put("contractId", contractId);
        send(first);
        send(second);

        awaitRows(contractId, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT source_event_id) FROM audit_record WHERE subject_id = ?",
                Integer.class, contractId)).isEqualTo(2);
    }

    @Test
    void malformedPayloadGoesToTheDlqAfterOneAttempt() throws Exception {
        Map<String, Object> event = ContractSignedConsumerTest.validEvent();
        ContractSignedConsumerTest.payload(event).remove("signedContentHash");
        send(event);

        await().atMost(Duration.ofSeconds(20)).until(() -> {
            Message dead = rabbit.receive(RabbitConfiguration.CONTRACT_SIGNED_DLQ, 500);
            return dead != null && JSON.readTree(dead.getBody()).get("eventId").asText().equals(event.get("eventId"));
        });
        assertThat(count("source_event_id", (String) event.get("eventId"))).isZero();
    }

    private void awaitRows(String subjectOrEventId, int expected) {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE subject_id = ? OR source_event_id = ?", Integer.class,
                subjectOrEventId, subjectOrEventId) >= expected);
    }

    private int count(String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE " + column + " = ?", Integer.class, value);
    }

    private void send(Map<String, Object> event) throws Exception {
        Message message = MessageBuilder.withBody(JSON.writeValueAsBytes(event))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbit.send(RabbitConfiguration.CONTRACTS_EXCHANGE, (String) event.get("eventType"), message);
    }
}
