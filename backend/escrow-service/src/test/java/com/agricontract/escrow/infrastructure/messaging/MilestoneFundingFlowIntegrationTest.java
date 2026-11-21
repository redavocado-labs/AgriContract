package com.agricontract.escrow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agricontract.escrow.infrastructure.config.RabbitMQConfig;
import com.agricontract.escrow.infrastructure.contract.FrozenSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * #120 on real MySQL and RabbitMQ: milestone.funding_requested in, bank.lock_requested LOCK_MILESTONE out, bank
 * results in, escrow.milestone_funded / escrow.milestone_funding_failed out. Retries use a 1s backoff here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MilestoneFundingFlowIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42").withDatabaseName("escrow_db");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final String LOCK_COMMANDS = "test.milestone-flow.lock-commands";
    static final String FUNDED_EVENTS = "test.milestone-flow.funded";
    static final String FAILED_EVENTS = "test.milestone-flow.funding-failed";
    static final String COMPLETED_DLQ = RabbitMQConfig.LOCK_COMPLETED_QUEUE + ".dlq";
    static final ObjectMapper JSON = new ObjectMapper();

    static {
        MYSQL.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("agricontract.eventing.outbox.poll-interval", () -> "PT0.2S");
        registry.add("escrow.deposit-lock.retry-backoff", () -> "PT1S,PT1S,PT1S");
        registry.add("escrow.deposit-lock.retry-poll-interval", () -> "PT0.2S");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void bindTestQueues() {
        TopicExchange escrow = new TopicExchange("agricontract.escrow");
        bind(escrow, LOCK_COMMANDS, "bank.lock_requested");
        bind(escrow, FUNDED_EVENTS, "escrow.milestone_funded");
        bind(escrow, FAILED_EVENTS, "escrow.milestone_funding_failed");
        admin.purgeQueue(COMPLETED_DLQ, false);
    }

    @Test
    void request_locksTheBatchFromTheBuyer_thenReportsFundedOnce() throws Exception {
        Map<String, Object> request = fundingRequested("300000000.00");
        send(request, "agricontract.contracts");

        JsonNode command = receiveOne(LOCK_COMMANDS);
        FrozenSchemas.assertEvent("bankLockRequested", command);
        Map<String, Object> payload = payload(request);
        assertThat(command.at("/payload/entryType").asText()).isEqualTo("LOCK_MILESTONE");
        assertThat(command.at("/payload/fundType").asText()).isEqualTo("MILESTONE_PAYMENT");
        assertThat(command.at("/payload/milestoneId").asText()).isEqualTo(payload.get("milestoneId"));
        assertThat(command.at("/payload/userId").asText()).isEqualTo(payload.get("buyerId"));
        assertThat(command.at("/payload/amount").asText()).isEqualTo("300000000.00");
        assertThat(command.at("/payload/sourceEventId").asText()).isEqualTo(command.get("eventId").asText());
        assertThat(command.get("correlationId").asText()).isEqualTo(request.get("correlationId"));
        assertThat(command.get("causationId").asText()).isEqualTo(request.get("eventId"));

        Instant bankTime = Instant.now().plusSeconds(1);
        send(bankResult(command, true, bankTime));
        JsonNode funded = receiveOne(FUNDED_EVENTS);
        FrozenSchemas.assertEvent("escrowMilestoneFunded", funded);
        assertThat(funded.at("/payload/milestoneId").asText()).isEqualTo(payload.get("milestoneId"));
        assertThat(Instant.parse(funded.at("/payload/lockedAt").asText())).isEqualTo(bankTime);
        assertThat(funded.get("correlationId").asText()).isEqualTo(request.get("correlationId"));
        assertThat(status(payload)).isEqualTo("LOCKED");

        send(bankResult(command, true, bankTime));                     // bank replay: new eventId, same request
        assertThat(rabbit.receive(FUNDED_EVENTS, 2_000)).isNull();
    }

    @Test
    void secondRequestForTheSameMilestone_sendsNoSecondLock_whateverTheAmount() throws Exception {
        Map<String, Object> request = fundingRequested("300000000.00");
        send(request, "agricontract.contracts");
        receiveOne(LOCK_COMMANDS);

        send(again(request, "300000000.00"), "agricontract.contracts");
        send(again(request, "320000000.00"), "agricontract.contracts");

        assertThat(rabbit.receive(LOCK_COMMANDS, 3_000)).isNull();
        assertThat(jdbc.queryForObject("SELECT batch_amount FROM escrow_milestone WHERE milestone_id = ?",
                String.class, payload(request).get("milestoneId"))).isEqualTo("300000000.00");
    }

    @Test
    void failedLock_isResentWithTheSameEventId_thenFailsAfterTheLastRetry() throws Exception {
        Map<String, Object> request = fundingRequested("150000000.00");
        send(request, "agricontract.contracts");
        JsonNode command = receiveOne(LOCK_COMMANDS);

        for (int retry = 1; retry <= 3; retry++) {
            send(bankResult(command, false, Instant.now()));
            JsonNode resent = receiveOne(LOCK_COMMANDS);
            assertThat(resent.get("eventId").asText()).isEqualTo(command.get("eventId").asText());
            assertThat(rabbit.receive(FAILED_EVENTS, 200)).isNull();
        }
        send(bankResult(command, false, Instant.now()));

        JsonNode failed = receiveOne(FAILED_EVENTS);
        FrozenSchemas.assertEvent("escrowMilestoneFundingFailed", failed);
        assertThat(failed.at("/payload/milestoneId").asText()).isEqualTo(payload(request).get("milestoneId"));
        assertThat(failed.at("/payload/reason").asText()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(status(payload(request))).isEqualTo("FUNDING_FAILED");
        assertThat(rabbit.receive(LOCK_COMMANDS, 2_500)).as("no resend after the last retry").isNull();
    }

    @Test
    void lockAfterTheFundingFailed_stillReportsFunded() throws Exception {
        Map<String, Object> request = fundingRequested("150000000.00");
        send(request, "agricontract.contracts");
        JsonNode command = receiveOne(LOCK_COMMANDS);
        for (int retry = 1; retry <= 3; retry++) {
            send(bankResult(command, false, Instant.now()));
            receiveOne(LOCK_COMMANDS);
        }
        send(bankResult(command, false, Instant.now()));
        receiveOne(FAILED_EVENTS);

        send(bankResult(command, true, Instant.now()));

        FrozenSchemas.assertEvent("escrowMilestoneFunded", receiveOne(FUNDED_EVENTS));
        assertThat(status(payload(request))).isEqualTo("LOCKED");
    }

    @Test
    void resultThatDoesNotMatchTheRequest_goesToTheDlq() throws Exception {
        Map<String, Object> request = fundingRequested("150000000.00");
        send(request, "agricontract.contracts");
        JsonNode command = receiveOne(LOCK_COMMANDS);

        Map<String, Object> wrongAmount = bankResult(command, true, Instant.now());
        payload(wrongAmount).put("amount", "150000001.00");
        send(wrongAmount);

        assertThat(receiveOne(COMPLETED_DLQ)).isNotNull();
        assertThat(status(payload(request))).isEqualTo("FUNDING_PENDING");
        assertThat(rabbit.receive(FUNDED_EVENTS, 1_000)).isNull();
    }

    private void bind(TopicExchange exchange, String name, String routingKey) {
        Queue queue = new Queue(name, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
        admin.purgeQueue(name, false);
    }

    private static Map<String, Object> fundingRequested(String batchAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", UUID.randomUUID().toString());
        payload.put("milestoneId", UUID.randomUUID().toString());
        payload.put("milestoneIndex", 1);
        payload.put("buyerId", UUID.randomUUID().toString());
        payload.put("batchAmount", batchAmount);
        payload.put("requestedAt", Instant.now().toString());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "milestone.funding_requested");
        event.put("eventVersion", 1);
        event.put("occurredAt", Instant.now().toString());
        event.put("producer", "contract-service");
        event.put("aggregateId", payload.get("contractId"));
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("causationId", null);
        event.put("payload", payload);
        return event;
    }

    /** The same milestone requested again under a new eventId. */
    private static Map<String, Object> again(Map<String, Object> request, String batchAmount) {
        Map<String, Object> event = new LinkedHashMap<>(request);
        Map<String, Object> payload = new LinkedHashMap<>(payload(request));
        payload.put("batchAmount", batchAmount);
        event.put("eventId", UUID.randomUUID().toString());
        event.put("payload", payload);
        return event;
    }

    private static Map<String, Object> bankResult(JsonNode command, boolean locked, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : List.of("contractId", "milestoneId", "userId", "entryType", "fundType", "amount",
                "sourceEventId")) {
            payload.put(field, command.at("/payload/" + field).asText());
        }
        payload.put("remedyDecisionId", null);
        payload.put("remedyLegId", null);
        if (locked) {
            payload.put("ledgerEntryId", UUID.randomUUID().toString());
        } else {
            payload.put("failureReason", "INSUFFICIENT_BALANCE");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", locked ? "bank.lock_completed" : "bank.lock_failed");
        event.put("eventVersion", 1);
        event.put("occurredAt", occurredAt.toString());
        event.put("producer", "bank-service");
        event.put("aggregateId", payload.get("contractId"));
        event.put("correlationId", command.get("correlationId").asText());
        event.put("causationId", command.get("eventId").asText());
        event.put("payload", payload);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    private String status(Map<String, Object> payload) {
        return jdbc.queryForObject("SELECT status FROM escrow_milestone WHERE milestone_id = ?", String.class,
                payload.get("milestoneId"));
    }

    private void send(Map<String, Object> event) throws Exception {
        send(event, "agricontract.bank");
    }

    private void send(Map<String, Object> event, String exchange) throws Exception {
        Message message = MessageBuilder.withBody(JSON.writeValueAsBytes(event))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbit.send(exchange, (String) event.get("eventType"), message);
    }

    private JsonNode receiveOne(String queue) throws Exception {
        List<JsonNode> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            Message message = rabbit.receive(queue, 500);
            if (message != null) {
                received.add(JSON.readTree(message.getBody()));
            }
            return !received.isEmpty();
        });
        return received.get(0);
    }
}
