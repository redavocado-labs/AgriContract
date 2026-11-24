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
 * #609 on real MySQL and RabbitMQ with the bank's ledger stubbed over local HTTP: escrow.contract_lock_cleared once
 * every remedy leg is COMPLETED and the ledger holds 0.00, OPERATOR's contract.remedy_retry_requested resending the
 * FAILED legs, and the two operator alert commands. The clearance check is ticked by hand (its interval is an hour).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContractLockClearedFlowIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42").withDatabaseName("escrow_db");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    static final String RELEASE_COMMANDS = "test.lock-cleared.release-commands";
    static final String REFUND_COMMANDS = "test.lock-cleared.refund-commands";
    static final String SEIZE_COMMANDS = "test.lock-cleared.seize-commands";
    static final String LOCK_CLEARED = "test.lock-cleared.events";
    static final String LEG_ALERTS = "test.lock-cleared.leg-alerts";
    static final String REJECTED_ALERTS = "test.lock-cleared.rejected-alerts";
    static final String RETRY_DLQ = RabbitMQConfig.REMEDY_RETRY_QUEUE + ".dlq";
    static final ObjectMapper JSON = new ObjectMapper();

    static final AtomicInteger LEDGER_STATUS = new AtomicInteger(200);
    static final AtomicReference<String> LEDGER_BODY = new AtomicReference<>("[]");
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
        registry.add("escrow.deposit-lock.retry-backoff", () -> "PT1S");          // second refusal = exhausted
        registry.add("escrow.deposit-lock.retry-poll-interval", () -> "PT0.2S");
        registry.add("escrow.clearance.check-interval", () -> "PT1H");
        registry.add("bank-service.url", () -> "http://127.0.0.1:" + BANK.getAddress().getPort());
        registry.add("service.internal-secret", () -> "test-secret");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContractClearanceScheduler clearance;

    @BeforeEach
    void bindTestQueues() {
        TopicExchange escrow = new TopicExchange("agricontract.escrow");
        bind(escrow, RELEASE_COMMANDS, "bank.release_requested");
        bind(escrow, REFUND_COMMANDS, "bank.refund_requested");
        bind(escrow, SEIZE_COMMANDS, "bank.seize_requested");
        bind(escrow, LOCK_CLEARED, "escrow.contract_lock_cleared");
        bind(escrow, LEG_ALERTS, "notification.escrow_remedy_leg_alert_requested");
        bind(escrow, REJECTED_ALERTS, "notification.escrow_remedy_rejected_requested");
        admin.purgeQueue(RETRY_DLQ, false);
        jdbc.update("DELETE FROM contract_clearance_check");                       // earlier tests' contracts
        LEDGER_STATUS.set(200);
    }

    @Test
    void allLegsCompleted_ledgerAtZero_publishesOnce_withEveryAcceptedDecision() throws Exception {
        Contract c = new Contract();
        seedDepositLeg(c);
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00"));
        String forfeitureDecision = id();
        String finalDecision = id();
        send(remedyFinalized(c, forfeitureDecision, List.of(),
                leg(id(), "DEPOSIT_FORFEITURE", "SELLER_DEPOSIT", "BUYER", "5000000.00")), "agricontract.contracts");
        JsonNode seize = receiveOne(SEIZE_COMMANDS);
        send(remedyFinalized(c, finalDecision, List.of(),
                leg(id(), "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.00")), "agricontract.contracts");
        JsonNode refund = receiveOne(REFUND_COMMANDS);

        // The final decision's refund completes first: the earlier forfeiture is still SENT.
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00"),
                entry("REFUND_TO_BUYER", "BUYER_DEPOSIT", null, "10000000.00"));
        completeAndTick(refund);
        assertThat(rabbit.receive(LOCK_CLEARED, 1_500)).isNull();
        assertThat(checkedAt(c)).as("a leg in flight marks the row checked").isNotNull();

        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00"),
                entry("REFUND_TO_BUYER", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("DEPOSIT_FORFEITURE", "SELLER_DEPOSIT", null, "5000000.00"));
        completeAndTick(seize);

        JsonNode cleared = receiveOne(LOCK_CLEARED);
        FrozenSchemas.assertEvent("escrowContractLockCleared", cleared);
        assertThat(cleared.at("/payload/contractId").asText()).isEqualTo(c.contractId);
        assertThat(cleared.at("/payload/remedyDecisionIds")).extracting(JsonNode::asText)
                .containsExactlyElementsOf(sorted(forfeitureDecision, finalDecision));

        clearance.checkRequestedContracts();                                       // checked: nothing more
        assertThat(rabbit.receive(LOCK_CLEARED, 1_500)).isNull();
    }

    @Test
    void failedLeg_publishesNothing_untilItCompletes() throws Exception {
        Contract c = new Contract();
        seedDepositLeg(c);
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00"));
        send(remedyFinalized(c, c.decisionId, List.of(),
                leg(id(), "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.00"),
                leg(id(), "PAYMENT_SETTLEMENT", "SELLER_DEPOSIT", "SELLER", "5000000.00")), "agricontract.contracts");
        JsonNode refund = receiveOne(REFUND_COMMANDS);
        JsonNode release = receiveOne(RELEASE_COMMANDS);

        send(result(release, false));
        await().atMost(Duration.ofSeconds(10)).until(() -> "FAILED".equals(legStatus(release)));
        completeAndTick(refund);
        assertThat(rabbit.receive(LOCK_CLEARED, 1_500)).as("a FAILED leg publishes nothing").isNull();

        JsonNode resent = receiveOne(RELEASE_COMMANDS);                             // the scheduled resend
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00"),
                entry("REFUND_TO_BUYER", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("RELEASE_TO_SELLER", "SELLER_DEPOSIT", null, "5000000.00"));
        completeAndTick(resent);
        JsonNode cleared = receiveOne(LOCK_CLEARED);
        assertThat(cleared.at("/payload/remedyDecisionIds")).extracting(JsonNode::asText)
                .containsExactly(c.decisionId);
    }

    @Test
    void milestoneMoneyStillLocked_publishesNothing_untilTheSettlementLegCompletes() throws Exception {
        Contract c = new Contract();
        seedDepositLeg(c);
        String releaseRequest = seedSettlingMilestone(c, "100.00");
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_MILESTONE", "MILESTONE_PAYMENT", c.milestoneId, "100.00"));
        send(remedyFinalized(c, c.decisionId, List.of(),
                leg(id(), "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.00")), "agricontract.contracts");
        JsonNode refund = receiveOne(REFUND_COMMANDS);

        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_MILESTONE", "MILESTONE_PAYMENT", c.milestoneId, "100.00"),
                entry("REFUND_TO_BUYER", "BUYER_DEPOSIT", null, "10000000.00"));
        completeAndTick(refund);
        assertThat(rabbit.receive(LOCK_CLEARED, 1_500)).as("100.00 still locked").isNull();
        assertThat(checkedAt(c)).isNotNull();

        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_MILESTONE", "MILESTONE_PAYMENT", c.milestoneId, "100.00"),
                entry("REFUND_TO_BUYER", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("RELEASE_TO_SELLER", "MILESTONE_PAYMENT", c.milestoneId, "100.00"));
        send(settlementReleaseCompleted(c, releaseRequest, "100.00"));
        await().atMost(Duration.ofSeconds(10)).until(() -> checkedAt(c) == null);   // requested again
        clearance.checkRequestedContracts();
        JsonNode cleared = receiveOne(LOCK_CLEARED);
        assertThat(cleared.at("/payload/contractId").asText()).isEqualTo(c.contractId);
    }

    @Test
    void ledgerUnavailable_leavesTheRowUnchecked_andTheNextTickPublishes() throws Exception {
        Contract c = new Contract();
        seedDepositLeg(c);
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"));
        send(remedyFinalized(c, c.decisionId, List.of(),
                leg(id(), "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.00")), "agricontract.contracts");
        JsonNode refund = receiveOne(REFUND_COMMANDS);

        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("REFUND_TO_BUYER", "BUYER_DEPOSIT", null, "10000000.00"));
        LEDGER_STATUS.set(503);
        completeAndTick(refund);
        assertThat(rabbit.receive(LOCK_CLEARED, 1_500)).isNull();
        assertThat(rowExists(c)).isTrue();
        assertThat(checkedAt(c)).as("left for the next tick").isNull();

        LEDGER_STATUS.set(200);
        clearance.checkRequestedContracts();
        assertThat(receiveOne(LOCK_CLEARED).at("/payload/contractId").asText()).isEqualTo(c.contractId);
        assertThat(checkedAt(c)).isNotNull();
    }

    @Test
    void exhaustedLeg_alertsOperatorOnce_andRetryResendsTheSameCommand() throws Exception {
        Contract c = new Contract();
        seedDepositLeg(c);
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00"));
        String seizeLeg = id();
        send(remedyFinalized(c, c.decisionId, List.of(),
                leg(id(), "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.00"),
                leg(seizeLeg, "DEPOSIT_FORFEITURE", "SELLER_DEPOSIT", "BUYER", "5000000.00")),
                "agricontract.contracts");
        JsonNode refund = receiveOne(REFUND_COMMANDS);
        JsonNode seize = receiveOne(SEIZE_COMMANDS);
        send(result(refund, true));

        send(result(seize, false));
        JsonNode resent = receiveOne(SEIZE_COMMANDS);                               // one scheduled resend
        assertThat(rabbit.receive(LEG_ALERTS, 500)).as("no alert while a resend is left").isNull();
        send(result(resent, false));
        await().atMost(Duration.ofSeconds(10)).until(() -> "FAILED".equals(legStatus(seize))
                && jdbc.queryForObject("SELECT next_attempt_at IS NULL FROM remedy_leg WHERE remedy_leg_id = ?",
                Boolean.class, seizeLeg));

        JsonNode alert = receiveOne(LEG_ALERTS);
        FrozenSchemas.assertCommand("escrowRemedyLegAlertRequested", alert);
        JsonNode p = alert.get("payload");
        assertThat(p.get("contractId").asText()).isEqualTo(c.contractId);
        assertThat(p.get("remedyDecisionId").asText()).isEqualTo(c.decisionId);
        assertThat(p.get("remedyLegId").asText()).isEqualTo(seizeLeg);
        assertThat(p.get("party").asText()).as("the payee of a seller deposit forfeiture").isEqualTo("BUYER");
        assertThat(p.get("amount").asText()).isEqualTo("5000000.00");
        assertThat(p.get("bankCommandEventId").asText()).isEqualTo(seize.get("eventId").asText());
        assertThat(rabbit.receive(LEG_ALERTS, 1_000)).as("one alert").isNull();
        assertThat(rabbit.receive(SEIZE_COMMANDS, 1_500)).as("no resend left").isNull();

        Map<String, Object> retry = remedyRetry(c.contractId);
        send(retry, "agricontract.contracts");
        JsonNode restarted = receiveOne(SEIZE_COMMANDS);
        assertThat(restarted.get("eventId").asText()).isEqualTo(seize.get("eventId").asText());
        assertThat(restarted.get("payload")).isEqualTo(seize.get("payload"));
        assertThat(legStatus(seize)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT attempts FROM remedy_leg WHERE remedy_leg_id = ?", Integer.class,
                seizeLeg)).isZero();
        assertThat(rabbit.receive(REFUND_COMMANDS, 500)).as("a COMPLETED leg is never resent").isNull();

        send(retry, "agricontract.contracts");                                     // same eventId: deduplicated
        assertThat(rabbit.receive(SEIZE_COMMANDS, 2_000)).isNull();
        assertThat(processed(retry)).isEqualTo(1);
    }

    @Test
    void retryForAContractWithoutLegs_isAcked_andSendsNothing() throws Exception {
        Map<String, Object> retry = remedyRetry(id());
        send(retry, "agricontract.contracts");

        await().atMost(Duration.ofSeconds(10)).until(() -> processed(retry) == 1);
        assertThat(rabbit.receive(RETRY_DLQ, 500)).isNull();
        assertThat(rabbit.receive(SEIZE_COMMANDS, 200)).isNull();
    }

    @Test
    void malformedRetry_isDeadLetteredWithoutRetry() throws Exception {
        Map<String, Object> retry = remedyRetry(id());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) retry.get("payload"));
        payload.put("reason", "not in the schema");
        retry.put("payload", payload);
        long start = System.nanoTime();
        send(retry, "agricontract.contracts");

        assertThat(receiveOne(RETRY_DLQ).get("eventId").asText()).isEqualTo(retry.get("eventId"));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no 1s + 2s retry backoff")
                .isLessThan(Duration.ofMillis(2_500));
        assertThat(processed(retry)).isZero();
    }

    @Test
    void rejectedDecision_alertsOperatorOnce_andAReplayWritesNothing() throws Exception {
        Contract c = new Contract();
        seedDepositLeg(c);
        ledger(c, entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"));
        Map<String, Object> event = remedyFinalized(c, c.decisionId, List.of(),
                leg(id(), "PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10000000.01"));
        send(event, "agricontract.contracts");

        JsonNode alert = receiveOne(REJECTED_ALERTS);
        FrozenSchemas.assertCommand("escrowRemedyRejectedRequested", alert);
        assertThat(alert.at("/payload/contractId").asText()).isEqualTo(c.contractId);
        assertThat(alert.at("/payload/remedyDecisionId").asText()).isEqualTo(c.decisionId);
        assertThat(alert.at("/payload/reason").asText()).contains("exceed");
        assertThat(alert.get("causationId").asText()).isEqualTo(event.get("eventId"));

        Map<String, Object> replay = new LinkedHashMap<>(event);
        replay.put("eventId", id());
        send(replay, "agricontract.contracts");
        await().atMost(Duration.ofSeconds(10)).until(() -> processed(replay) == 1);
        assertThat(rabbit.receive(REJECTED_ALERTS, 1_500)).as("a replay of a REJECTED decision").isNull();
    }

    /** The bank confirms the command, then the clearance check runs once the completion is committed. */
    private void completeAndTick(JsonNode command) throws Exception {
        send(result(command, true));
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(legStatus(command)));
        clearance.checkRequestedContracts();
    }

    private static List<String> sorted(String... ids) {
        return java.util.Arrays.stream(ids).sorted().toList();
    }

    private void seedDepositLeg(Contract c) {
        jdbc.update("INSERT INTO deposit_leg (leg_id, contract_id, leg, user_id, amount, status, request_event_id,"
                + " ledger_entry_id, created_at, updated_at) VALUES (?, ?, 'BUYER', ?, 10000000.00, 'LOCKED', ?, ?,"
                + " NOW(6), NOW(6))", id(), c.contractId, c.buyerId, id(), id());
    }

    /** A milestone being settled with its release to the seller still out; returns the command's eventId. */
    private String seedSettlingMilestone(Contract c, String amount) {
        String settlementEvent = id();
        jdbc.update("INSERT INTO escrow_milestone (milestone_id, contract_id, milestone_index, buyer_id, batch_amount,"
                + " funding_requested_at, funding_request_event_id, status, request_event_id, ledger_entry_id,"
                + " locked_at, settlement_event_id, created_at, updated_at) VALUES (?, ?, 1, ?, ?, NOW(6), ?,"
                + " 'SETTLING', ?, ?, NOW(6), ?, NOW(6), NOW(6))", c.milestoneId, c.contractId, c.buyerId, amount,
                id(), id(), id(), settlementEvent);
        String request = id();
        jdbc.update("INSERT INTO settlement_leg (leg_id, contract_id, milestone_id, trigger_event_id, entry_type,"
                + " fund_type, user_id, amount, request_event_id, status, created_at, updated_at) VALUES (?, ?, ?, ?,"
                + " 'RELEASE_TO_SELLER', 'MILESTONE_PAYMENT', ?, ?, ?, 'REQUESTED', NOW(6), NOW(6))", id(),
                c.contractId, c.milestoneId, settlementEvent, c.sellerId, amount, request);
        return request;
    }

    private static Map<String, Object> settlementReleaseCompleted(Contract c, String request, String amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", c.contractId);
        payload.put("milestoneId", c.milestoneId);
        payload.put("userId", c.sellerId);
        payload.put("entryType", "RELEASE_TO_SELLER");
        payload.put("fundType", "MILESTONE_PAYMENT");
        payload.put("remedyDecisionId", null);
        payload.put("remedyLegId", null);
        payload.put("amount", amount);
        payload.put("sourceEventId", request);
        payload.put("ledgerEntryId", id());
        Map<String, Object> event = envelope("bank.release_completed", "bank-service", payload);
        event.put("causationId", request);
        return event;
    }

    private static Map<String, Object> remedyFinalized(Contract c, String decisionId, List<String> milestones,
                                                       Map<String, Object>... legs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("remedyDecisionId", decisionId);
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

    private static Map<String, Object> remedyRetry(String contractId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("operatorUserId", id());
        payload.put("requestedAt", Instant.now().toString());
        return envelope("contract.remedy_retry_requested", "contract-service", payload);
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

    /** The bank's answer to a remedy command: bank.<op>_completed or bank.<op>_failed with the command's fields. */
    private static Map<String, Object> result(JsonNode command, boolean completed) {
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
        String operation = command.get("eventType").asText().replace("_requested", "");
        Map<String, Object> event = envelope(operation + (completed ? "_completed" : "_failed"), "bank-service",
                payload);
        event.put("causationId", command.get("eventId").asText());
        return event;
    }

    private static Map<String, Object> entry(String entryType, String fundType, String milestoneId, String amount) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("entryId", id());
        entry.put("entryType", entryType);
        entry.put("fundType", fundType);
        entry.put("milestoneId", milestoneId);
        entry.put("amount", amount);
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

    private String legStatus(JsonNode command) {
        return jdbc.queryForObject("SELECT status FROM remedy_leg WHERE remedy_leg_id = ?", String.class,
                command.at("/payload/remedyLegId").asText());
    }

    private boolean rowExists(Contract c) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM contract_clearance_check WHERE contract_id = ?",
                Integer.class, c.contractId) == 1;
    }

    private Instant checkedAt(Contract c) {
        List<java.sql.Timestamp> rows = jdbc.queryForList(
                "SELECT checked_at FROM contract_clearance_check WHERE contract_id = ?", java.sql.Timestamp.class,
                c.contractId);
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toInstant();
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
