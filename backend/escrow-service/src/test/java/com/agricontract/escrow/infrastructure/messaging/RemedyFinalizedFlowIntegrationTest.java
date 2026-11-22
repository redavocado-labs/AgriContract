package com.agricontract.escrow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agricontract.escrow.infrastructure.config.RabbitMQConfig;
import com.agricontract.escrow.infrastructure.contract.FrozenSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * #249 on real MySQL and RabbitMQ with the bank's ledger stubbed over local HTTP: remedy.finalized in, the exact
 * release / refund / seize commands out, conservation failures write nothing, results settle or resend the legs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RemedyFinalizedFlowIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42").withDatabaseName("escrow_db");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final String RELEASE_COMMANDS = "test.remedy-flow.release-commands";
    static final String REFUND_COMMANDS = "test.remedy-flow.refund-commands";
    static final String SEIZE_COMMANDS = "test.remedy-flow.seize-commands";
    static final String REMEDY_DLQ = RabbitMQConfig.REMEDY_FINALIZED_QUEUE + ".dlq";
    static final ObjectMapper JSON = new ObjectMapper();

    static final AtomicInteger LEDGER_STATUS = new AtomicInteger(200);
    static final AtomicInteger LEDGER_CALLS = new AtomicInteger();
    static final AtomicReference<String> LEDGER_BODY = new AtomicReference<>("[]");
    static final AtomicReference<String> LEDGER_SECRET = new AtomicReference<>();
    static final HttpServer BANK;

    static {
        MYSQL.start();
        RABBIT.start();
        try {
            BANK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        BANK.createContext("/internal/v1/bank/ledger", exchange -> {
            LEDGER_CALLS.incrementAndGet();
            LEDGER_SECRET.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            byte[] body = LEDGER_BODY.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(LEDGER_STATUS.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        BANK.start();
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
        registry.add("bank-service.url", () -> "http://127.0.0.1:" + BANK.getAddress().getPort());
        registry.add("service.internal-secret", () -> "test-secret");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void bindTestQueues() {
        TopicExchange escrow = new TopicExchange("agricontract.escrow");
        bind(escrow, RELEASE_COMMANDS, "bank.release_requested");
        bind(escrow, REFUND_COMMANDS, "bank.refund_requested");
        bind(escrow, SEIZE_COMMANDS, "bank.seize_requested");
        admin.purgeQueue(REMEDY_DLQ, false);
        LEDGER_STATUS.set(200);
        LEDGER_CALLS.set(0);
    }

    @Test
    void normalCompletion_sendsOneRefundAndOneRelease_withTheExactPayloads() throws Exception {
        Contract c = new Contract();
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00", c),
                entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00", c));
        String refundLeg = id();
        String releaseLeg = id();
        Map<String, Object> event = remedyFinalized(c, List.of(),
                leg(refundLeg, "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.00"),
                leg(releaseLeg, "PAYMENT_SETTLEMENT", "SELLER_DEPOSIT", "SELLER", "5000000.00"));
        send(event, "agricontract.contracts");

        JsonNode refund = receiveOne(REFUND_COMMANDS);
        JsonNode release = receiveOne(RELEASE_COMMANDS);
        FrozenSchemas.assertEvent("bankRefundRequested", refund);
        FrozenSchemas.assertEvent("bankReleaseRequested", release);
        assertCommand(refund, event, c, "REFUND_TO_BUYER", "BUYER_DEPOSIT", c.buyerId, null, "10000000.00",
                c.decisionId, refundLeg);
        assertCommand(release, event, c, "RELEASE_TO_SELLER", "SELLER_DEPOSIT", c.sellerId, null, "5000000.00",
                c.decisionId, releaseLeg);
        assertThat(LEDGER_SECRET.get()).isEqualTo("test-secret");
        assertThat(decisionStatus(c)).isEqualTo("ACCEPTED");
        assertThat(legCount(c)).isEqualTo(2);

        // Replay of the same eventId, then the same decision under a new eventId: nothing more is sent.
        send(event, "agricontract.contracts");
        send(withNewEventId(event), "agricontract.contracts");
        assertThat(rabbit.receive(REFUND_COMMANDS, 2_500)).isNull();
        assertThat(rabbit.receive(RELEASE_COMMANDS, 500)).isNull();
        assertThat(legCount(c)).isEqualTo(2);
    }

    @Test
    void forfeitureAndMilestoneLegs_goOutAsSeizeAndRelease() throws Exception {
        Contract c = new Contract();
        ledger(c, entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00", c),
                entry("LOCK_MILESTONE", "MILESTONE_PAYMENT", c.milestoneId, "100000000.00", c));
        String forfeit = id();
        String penalty = id();
        String refund = id();
        send(remedyFinalized(c, List.of(c.milestoneId),
                leg(forfeit, "DEPOSIT_FORFEITURE", "SELLER_DEPOSIT", "BUYER", "5000000.00"),
                leg(penalty, "CONTRACTUAL_PENALTY", "MILESTONE_PAYMENT", "BUYER", "20000000.00"),
                leg(refund, "PAYMENT_REFUND", "MILESTONE_PAYMENT", "BUYER", "80000000.00")),
                "agricontract.contracts");

        List<JsonNode> seizes = List.of(receiveOne(SEIZE_COMMANDS), receiveOne(SEIZE_COMMANDS));
        for (JsonNode seize : seizes) {
            FrozenSchemas.assertEvent("bankSeizeRequested", seize);
            assertThat(seize.at("/payload/userId").asText()).isEqualTo(c.buyerId);
            assertThat(seize.get("eventType").asText()).isEqualTo("bank.seize_requested");
        }
        assertThat(seizes).extracting(s -> s.at("/payload/entryType").asText())
                .containsExactlyInAnyOrder("DEPOSIT_FORFEITURE", "CONTRACTUAL_PENALTY");
        assertThat(seizes).filteredOn(s -> s.at("/payload/entryType").asText().equals("DEPOSIT_FORFEITURE"))
                .allSatisfy(s -> assertThat(s.at("/payload/milestoneId").isNull()).isTrue());
        assertThat(seizes).filteredOn(s -> s.at("/payload/entryType").asText().equals("CONTRACTUAL_PENALTY"))
                .allSatisfy(s -> assertThat(s.at("/payload/milestoneId").asText()).isEqualTo(c.milestoneId));
        JsonNode refundCommand = receiveOne(REFUND_COMMANDS);
        FrozenSchemas.assertEvent("bankRefundRequested", refundCommand);
        assertThat(refundCommand.at("/payload/milestoneId").asText()).isEqualTo(c.milestoneId);
    }

    @Test
    void oneCentOverTheLock_rejectsTheDecision_andSendsNothing() throws Exception {
        Contract c = new Contract();
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00", c));
        Map<String, Object> event = remedyFinalized(c, List.of(),
                leg(id(), "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.01"));
        send(event, "agricontract.contracts");

        await().atMost(Duration.ofSeconds(10)).until(() -> "REJECTED".equals(decisionStatus(c)));
        assertThat(legCount(c)).isZero();
        assertThat(processed(event)).as("acked, not retried").isEqualTo(1);
        assertThat(rabbit.receive(REFUND_COMMANDS, 1_500)).isNull();
        assertThat(rabbit.receive(REMEDY_DLQ, 200)).as("a rejected decision is not dead-lettered").isNull();

        send(withNewEventId(event), "agricontract.contracts");                       // replay: ignored
        assertThat(rabbit.receive(REFUND_COMMANDS, 1_500)).isNull();
    }

    @Test
    void bankDown_sendsNothing_isRetried_thenDeadLettered() throws Exception {
        Contract c = new Contract();
        LEDGER_STATUS.set(503);
        Map<String, Object> event = remedyFinalized(c, List.of(),
                leg(id(), "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10.00"));
        send(event, "agricontract.contracts");

        assertThat(receiveOne(REMEDY_DLQ).get("eventId").asText()).isEqualTo(event.get("eventId"));
        assertThat(LEDGER_CALLS.get()).as("retried by the listener").isGreaterThanOrEqualTo(2);
        assertThat(rabbit.receive(REFUND_COMMANDS, 500)).isNull();
        assertThat(legCount(c)).isZero();
        assertThat(processed(event)).isZero();
        assertThat(decisionStatus(c)).isNull();
    }

    @Test
    void seizeResults_completeTheLeg_orFailAndResendTheSameCommand() throws Exception {
        Contract c = new Contract();
        ledger(c, entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00", c));
        String legId = id();
        send(remedyFinalized(c, List.of(), leg(legId, "DEPOSIT_FORFEITURE", "SELLER_DEPOSIT", "BUYER", "5000000.00")),
                "agricontract.contracts");
        JsonNode seize = receiveOne(SEIZE_COMMANDS);
        assertThat(legStatus(legId)).isEqualTo("SENT");

        send(seizeResult(seize, false));
        JsonNode resent = receiveOne(SEIZE_COMMANDS);
        assertThat(resent.get("eventId").asText()).isEqualTo(seize.get("eventId").asText());
        assertThat(resent.at("/payload/sourceEventId").asText()).isEqualTo(seize.get("eventId").asText());
        assertThat(resent.at("/payload/remedyLegId").asText()).isEqualTo(legId);

        Map<String, Object> completed = seizeResult(resent, true);
        send(completed);
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(legStatus(legId)));
        send(seizeResult(resent, true));                                             // bank replay, new eventId
        send(seizeResult(resent, false));                                            // late refusal after completion
        Thread.sleep(1_000);
        assertThat(legStatus(legId)).isEqualTo("COMPLETED");
        assertThat(rabbit.receive(SEIZE_COMMANDS, 500)).isNull();
    }

    @Test
    void resultForAnUnknownRemedyLeg_isAcked() throws Exception {
        Contract c = new Contract();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", c.contractId);
        payload.put("milestoneId", null);
        payload.put("userId", c.buyerId);
        payload.put("entryType", "DEPOSIT_FORFEITURE");
        payload.put("fundType", "SELLER_DEPOSIT");
        payload.put("remedyDecisionId", id());
        payload.put("remedyLegId", id());
        payload.put("amount", "1.00");
        payload.put("sourceEventId", id());
        payload.put("ledgerEntryId", id());
        Map<String, Object> event = envelope("bank.seize_completed", "bank-service", payload);
        send(event);

        await().atMost(Duration.ofSeconds(10)).until(() -> processed(event) == 1);
        assertThat(rabbit.receive(RabbitMQConfig.SEIZE_COMPLETED_QUEUE + ".dlq", 500)).isNull();
    }

    private void assertCommand(JsonNode command, Map<String, Object> source, Contract c, String entryType,
                               String fundType, String userId, String milestoneId, String amount, String decisionId,
                               String legId) {
        JsonNode p = command.get("payload");
        assertThat(p.get("entryType").asText()).isEqualTo(entryType);
        assertThat(p.get("fundType").asText()).isEqualTo(fundType);
        assertThat(p.get("contractId").asText()).isEqualTo(c.contractId);
        assertThat(p.get("milestoneId").isNull()).isEqualTo(milestoneId == null);
        assertThat(p.get("userId").asText()).isEqualTo(userId);
        assertThat(p.get("amount").asText()).isEqualTo(amount);
        assertThat(p.get("remedyDecisionId").asText()).isEqualTo(decisionId);
        assertThat(p.get("remedyLegId").asText()).isEqualTo(legId);
        assertThat(p.get("sourceEventId").asText()).isEqualTo(command.get("eventId").asText());
        assertThat(command.get("correlationId").asText()).isEqualTo(source.get("correlationId"));
        assertThat(command.get("causationId").asText()).isEqualTo(source.get("eventId"));
    }

    private static Map<String, Object> remedyFinalized(Contract c, List<String> milestones,
                                                       Map<String, Object>... legs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("remedyDecisionId", c.decisionId);
        payload.put("attributionDecisionId", id());
        payload.put("breachCaseId", null);
        payload.put("contractId", c.contractId);
        payload.put("buyerId", c.buyerId);
        payload.put("sellerId", c.sellerId);
        payload.put("affectedMilestoneIds", milestones);
        payload.put("settledMilestoneCount", 0);
        payload.put("finalBreachingRole", null);
        payload.put("breachReasonCode", null);
        payload.put("qualityDisposition", null);
        payload.put("decisionSource", "SYSTEM");
        payload.put("penaltyEligible", false);
        payload.put("reputationEligible", false);
        payload.put("remedyLegs", List.of(legs));
        return envelope("remedy.finalized", "contract-service", payload);
    }

    private static Map<String, Object> leg(String legId, String type, String fund, String role, String amount) {
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("remedyLegId", legId);
        leg.put("remedyType", type);
        leg.put("fundType", fund);
        leg.put("role", role);
        leg.put("amount", amount);
        return leg;
    }

    private static Map<String, Object> withNewEventId(Map<String, Object> event) {
        Map<String, Object> copy = new LinkedHashMap<>(event);
        copy.put("eventId", UUID.randomUUID().toString());
        return copy;
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

    private static Map<String, Object> seizeResult(JsonNode command, boolean completed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : List.of("contractId", "milestoneId", "userId", "entryType", "fundType", "remedyDecisionId",
                "remedyLegId", "amount", "sourceEventId")) {
            JsonNode value = command.at("/payload/" + field);
            payload.put(field, value.isNull() ? null : value.asText());
        }
        if (completed) {
            payload.put("ledgerEntryId", id());
        } else {
            payload.put("failureReason", "KILL_SWITCH_ACTIVE");
        }
        Map<String, Object> event = envelope(completed ? "bank.seize_completed" : "bank.seize_failed", "bank-service",
                payload);
        event.put("causationId", command.get("eventId").asText());
        return event;
    }

    private static Map<String, Object> entry(String entryType, String fundType, String milestoneId, String amount,
                                             Contract c) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("entryId", id());
        entry.put("sourceEventId", id());
        entry.put("contractId", c.contractId);
        entry.put("milestoneId", milestoneId);
        entry.put("userId", c.buyerId);
        entry.put("entryType", entryType);
        entry.put("fundType", fundType);
        entry.put("remedyDecisionId", null);
        entry.put("remedyLegId", null);
        entry.put("amount", amount);
        entry.put("createdAt", Instant.now().toString());
        return entry;
    }

    @SafeVarargs
    private static void ledger(Contract c, Map<String, Object>... entries) throws Exception {
        LEDGER_BODY.set(JSON.writeValueAsString(List.of(entries)));
    }

    private void bind(TopicExchange exchange, String name, String routingKey) {
        Queue queue = new Queue(name, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
        admin.purgeQueue(name, false);
    }

    private int processed(Map<String, Object> event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class,
                event.get("eventId"));
    }

    private String decisionStatus(Contract c) {
        return jdbc.queryForList("SELECT status FROM remedy_decision WHERE remedy_decision_id = ?", String.class,
                c.decisionId).stream().findFirst().orElse(null);
    }

    private int legCount(Contract c) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM remedy_leg WHERE contract_id = ?", Integer.class,
                c.contractId);
    }

    private String legStatus(String legId) {
        return jdbc.queryForObject("SELECT status FROM remedy_leg WHERE remedy_leg_id = ?", String.class, legId);
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

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static final class Contract {
        final String contractId = id();
        final String decisionId = id();
        final String buyerId = id();
        final String sellerId = id();
        final String milestoneId = id();
    }
}
