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
 * #248 on real MySQL and RabbitMQ: a funded milestone, milestone.settled in, bank.release_requested /
 * bank.refund_requested out, bank results in, the milestone SETTLED only once the bank confirmed every leg.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MilestoneSettlementFlowIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42").withDatabaseName("escrow_db");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final String LOCK_COMMANDS = "test.settlement-flow.lock-commands";
    static final String RELEASE_COMMANDS = "test.settlement-flow.release-commands";
    static final String REFUND_COMMANDS = "test.settlement-flow.refund-commands";
    static final String FUNDED_EVENTS = "test.settlement-flow.funded";
    static final String SETTLED_DLQ = RabbitMQConfig.MILESTONE_SETTLED_QUEUE + ".dlq";
    static final String RELEASE_COMPLETED_DLQ = RabbitMQConfig.RELEASE_COMPLETED_QUEUE + ".dlq";
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
        bind(escrow, RELEASE_COMMANDS, "bank.release_requested");
        bind(escrow, REFUND_COMMANDS, "bank.refund_requested");
        bind(escrow, FUNDED_EVENTS, "escrow.milestone_funded");
        admin.purgeQueue(SETTLED_DLQ, false);
        admin.purgeQueue(RELEASE_COMPLETED_DLQ, false);
    }

    @Test
    void partialDelivery_releasesActual_refundsTheRest_settledOnlyOnBankResults() throws Exception {
        Funded funded = fund("300000000.00");
        Map<String, Object> settled = milestoneSettled(funded, "300000000.00", "292500000.00");
        send(settled, "agricontract.contracts");

        JsonNode release = receiveOne(RELEASE_COMMANDS);
        JsonNode refund = receiveOne(REFUND_COMMANDS);
        FrozenSchemas.assertEvent("bankReleaseRequested", release);
        FrozenSchemas.assertEvent("bankRefundRequested", refund);
        assertCommand(release, settled, funded, "RELEASE_TO_SELLER", funded.sellerId, "292500000.00");
        assertCommand(refund, settled, funded, "REFUND_TO_BUYER", funded.buyerId, "7500000.00");
        assertThat(status(funded)).isEqualTo("SETTLING");

        send(settled, "agricontract.contracts");                                     // redelivery, same eventId
        send(again(settled, "292500000.00"), "agricontract.contracts");              // repeat, other eventId
        send(again(settled, "300000000.00"), "agricontract.contracts");              // conflicting amounts
        assertThat(rabbit.receive(RELEASE_COMMANDS, 2_500)).as("replay emits no second command").isNull();
        assertThat(rabbit.receive(REFUND_COMMANDS, 500)).isNull();
        assertThat(legCount(funded)).isEqualTo(2);

        send(bankResult(release, true));
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(legStatus(release)));
        assertThat(status(funded)).as("one leg confirmed is not settled").isEqualTo("SETTLING");

        send(bankResult(refund, true));
        await().atMost(Duration.ofSeconds(10)).until(() -> "SETTLED".equals(status(funded)));
        send(bankResult(refund, true));                                              // bank replay: new eventId
        Thread.sleep(1_000);
        assertThat(status(funded)).isEqualTo("SETTLED");
    }

    @Test
    void fullDelivery_sendsNoZeroRefund() throws Exception {
        Funded funded = fund("150000000.00");
        send(milestoneSettled(funded, "150000000.00", "150000000.00"), "agricontract.contracts");

        JsonNode release = receiveOne(RELEASE_COMMANDS);
        assertThat(release.at("/payload/amount").asText()).isEqualTo("150000000.00");
        assertThat(rabbit.receive(REFUND_COMMANDS, 2_000)).isNull();

        send(bankResult(release, true));
        await().atMost(Duration.ofSeconds(10)).until(() -> "SETTLED".equals(status(funded)));
    }

    @Test
    void actualAboveTheLock_goesToTheDlq_andSendsNothing() throws Exception {
        Funded funded = fund("150000000.00");
        send(milestoneSettled(funded, "150000000.00", "150000001.00"), "agricontract.contracts");

        assertThat(receiveOne(SETTLED_DLQ)).isNotNull();
        assertThat(rabbit.receive(RELEASE_COMMANDS, 1_000)).isNull();
        assertThat(rabbit.receive(REFUND_COMMANDS, 500)).isNull();
        assertThat(status(funded)).isEqualTo("LOCKED");
    }

    @Test
    void refusedRelease_isResentWithTheSameEventId_thenSettles() throws Exception {
        Funded funded = fund("150000000.00");
        send(milestoneSettled(funded, "150000000.00", "150000000.00"), "agricontract.contracts");
        JsonNode release = receiveOne(RELEASE_COMMANDS);

        send(bankResult(release, false));
        JsonNode resent = receiveOne(RELEASE_COMMANDS);
        assertThat(resent.get("eventId").asText()).isEqualTo(release.get("eventId").asText());
        assertThat(resent.at("/payload/sourceEventId").asText()).isEqualTo(release.get("eventId").asText());
        assertThat(status(funded)).isEqualTo("SETTLING");

        send(bankResult(resent, true));
        await().atMost(Duration.ofSeconds(10)).until(() -> "SETTLED".equals(status(funded)));
    }

    @Test
    void resultThatDoesNotMatchTheLeg_goesToTheDlq() throws Exception {
        Funded funded = fund("150000000.00");
        send(milestoneSettled(funded, "150000000.00", "150000000.00"), "agricontract.contracts");
        JsonNode release = receiveOne(RELEASE_COMMANDS);

        Map<String, Object> wrongAmount = bankResult(release, true);
        payload(wrongAmount).put("amount", "149999999.00");
        send(wrongAmount);

        assertThat(receiveOne(RELEASE_COMPLETED_DLQ)).isNotNull();
        assertThat(legStatus(release)).isEqualTo("REQUESTED");
        assertThat(status(funded)).isEqualTo("SETTLING");
    }

    private void assertCommand(JsonNode command, Map<String, Object> settled, Funded funded, String entryType,
                               String userId, String amount) {
        assertThat(command.at("/payload/entryType").asText()).isEqualTo(entryType);
        assertThat(command.at("/payload/fundType").asText()).isEqualTo("MILESTONE_PAYMENT");
        assertThat(command.at("/payload/contractId").asText()).isEqualTo(funded.contractId);
        assertThat(command.at("/payload/milestoneId").asText()).isEqualTo(funded.milestoneId);
        assertThat(command.at("/payload/userId").asText()).isEqualTo(userId);
        assertThat(command.at("/payload/amount").asText()).isEqualTo(amount);
        assertThat(command.at("/payload/remedyDecisionId").isNull()).isTrue();
        assertThat(command.at("/payload/remedyLegId").isNull()).isTrue();
        assertThat(command.at("/payload/sourceEventId").asText()).isEqualTo(command.get("eventId").asText());
        assertThat(command.get("correlationId").asText()).isEqualTo(settled.get("correlationId"));
        assertThat(command.get("causationId").asText()).isEqualTo(settled.get("eventId"));
    }

    /** milestone.funding_requested, then bank.lock_completed: the milestone is LOCKED. */
    private Funded fund(String batchAmount) throws Exception {
        Funded funded = new Funded(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", funded.contractId);
        payload.put("milestoneId", funded.milestoneId);
        payload.put("milestoneIndex", 1);
        payload.put("buyerId", funded.buyerId);
        payload.put("batchAmount", batchAmount);
        payload.put("requestedAt", Instant.now().toString());
        send(envelope("milestone.funding_requested", "contract-service", payload), "agricontract.contracts");
        JsonNode lock = receiveOne(LOCK_COMMANDS);
        Map<String, Object> locked = bankResult(lock, true);
        locked.put("eventType", "bank.lock_completed");
        send(locked);
        receiveOne(FUNDED_EVENTS);
        assertThat(status(funded)).isEqualTo("LOCKED");
        return funded;
    }

    private static Map<String, Object> milestoneSettled(Funded funded, String lockedAmount, String actualAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", funded.contractId);
        payload.put("milestoneId", funded.milestoneId);
        payload.put("lockedAmount", lockedAmount);
        payload.put("actualAmount", actualAmount);
        payload.put("recipients", List.of(
                Map.of("userId", funded.buyerId, "email", "buyer@example.com", "role", "BUYER"),
                Map.of("userId", funded.sellerId, "email", "seller@example.com", "role", "SELLER")));
        return envelope("milestone.settled", "contract-service", payload);
    }

    /** The same milestone settled again under a new eventId. */
    private static Map<String, Object> again(Map<String, Object> settled, String actualAmount) {
        Map<String, Object> event = new LinkedHashMap<>(settled);
        Map<String, Object> payload = new LinkedHashMap<>(payload(settled));
        payload.put("actualAmount", actualAmount);
        event.put("eventId", UUID.randomUUID().toString());
        event.put("payload", payload);
        return event;
    }

    private static Map<String, Object> envelope(String eventType, String producer, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("eventVersion", 1);
        event.put("occurredAt", Instant.now().toString());
        event.put("producer", producer);
        event.put("aggregateId", payload.get("contractId"));
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("causationId", null);
        event.put("payload", payload);
        return event;
    }

    /** The bank's answer to a command: same fields, plus ledgerEntryId or failureReason. */
    private static Map<String, Object> bankResult(JsonNode command, boolean completed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : List.of("contractId", "milestoneId", "userId", "entryType", "fundType", "amount",
                "sourceEventId")) {
            payload.put(field, command.at("/payload/" + field).asText());
        }
        payload.put("remedyDecisionId", null);
        payload.put("remedyLegId", null);
        if (completed) {
            payload.put("ledgerEntryId", UUID.randomUUID().toString());
        } else {
            payload.put("failureReason", "KILL_SWITCH_ACTIVE");
        }
        String operation = command.get("eventType").asText().replace("_requested", "");
        Map<String, Object> event = envelope(operation + (completed ? "_completed" : "_failed"), "bank-service",
                payload);
        event.put("correlationId", command.get("correlationId").asText());
        event.put("causationId", command.get("eventId").asText());
        return event;
    }

    private void bind(TopicExchange exchange, String name, String routingKey) {
        Queue queue = new Queue(name, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
        admin.purgeQueue(name, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    private String status(Funded funded) {
        return jdbc.queryForObject("SELECT status FROM escrow_milestone WHERE milestone_id = ?", String.class,
                funded.milestoneId);
    }

    private String legStatus(JsonNode command) {
        return jdbc.queryForObject("SELECT status FROM settlement_leg WHERE request_event_id = ?", String.class,
                command.get("eventId").asText());
    }

    private int legCount(Funded funded) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_leg WHERE milestone_id = ?", Integer.class,
                funded.milestoneId);
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

    private record Funded(String contractId, String milestoneId, String buyerId, String sellerId) {
    }
}
