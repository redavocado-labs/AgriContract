package com.agricontract.bank.infrastructure.messaging;

import com.agricontract.bank.application.dto.LedgerCommand;
import com.agricontract.bank.application.dto.LedgerOperation;
import com.agricontract.bank.domain.model.EntryType;
import com.agricontract.bank.domain.model.FundType;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerCommandConsumerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final LedgerCommandConsumer consumer = new LedgerCommandConsumer(new EventEnvelopeCodec(), command -> { });

    @Test
    void readsAValidRequest() throws Exception {
        Map<String, Object> event = validRequest();
        LedgerCommand command = consumer.toCommand(JSON.writeValueAsBytes(event), LedgerOperation.LOCK);

        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(command.requestEventId()).isEqualTo(event.get("eventId"));
        assertThat(command.sourceEventId()).isEqualTo(event.get("eventId"));
        assertThat(command.correlationId()).isEqualTo(event.get("correlationId"));
        assertThat(command.contractId()).isEqualTo(payload.get("contractId"));
        assertThat(command.milestoneId()).isNull();
        assertThat(command.entryType()).isEqualTo(EntryType.LOCK_BUYER_DEPOSIT);
        assertThat(command.fundType()).isEqualTo(FundType.BUYER_DEPOSIT);
        assertThat(command.amount()).isEqualByComparingTo(new BigDecimal("40000000.00"));
    }

    /** #124/#138: release and refund commands share the lock payload; each queue takes only its own entry type. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "bank.release_requested, RELEASE_TO_SELLER, SELLER_DEPOSIT, RELEASE",
            "bank.release_requested, RELEASE_TO_SELLER, MILESTONE_PAYMENT, RELEASE",
            "bank.refund_requested, REFUND_TO_BUYER, BUYER_DEPOSIT, REFUND",
            "bank.refund_requested, REFUND_TO_BUYER, MILESTONE_PAYMENT, REFUND"})
    void readsReleaseAndRefundRequests(String eventType, String entryType, String fundType, LedgerOperation operation)
            throws Exception {
        Map<String, Object> event = request(eventType, entryType, fundType);

        LedgerCommand command = consumer.toCommand(JSON.writeValueAsBytes(event), operation);

        assertThat(command.entryType()).isEqualTo(EntryType.valueOf(entryType));
        assertThat(command.operation()).isEqualTo(operation);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"CONTRACTUAL_PENALTY, MILESTONE_PAYMENT", "DAMAGES_COMPENSATION, MILESTONE_PAYMENT",
            "DEPOSIT_FORFEITURE, SELLER_DEPOSIT"})
    void readsSeizeRequests(String entryType, String fundType) throws Exception {
        LedgerCommand command = consumer.toCommand(JSON.writeValueAsBytes(seizeRequest(entryType, fundType)),
                LedgerOperation.SEIZE);

        assertThat(command.operation()).isEqualTo(LedgerOperation.SEIZE);
        assertThat(command.remedyDecisionId()).isNotNull();
        assertThat(command.remedyLegId()).isNotNull();
    }

    @ParameterizedTest(name = "seize without {0}")
    @CsvSource({"remedyDecisionId", "remedyLegId"})
    void seizeWithANullRemedyReferenceIsInvalid(String field) throws Exception {
        Map<String, Object> event = seizeRequest("CONTRACTUAL_PENALTY", "MILESTONE_PAYMENT");
        payload(event).put(field, null);
        byte[] body = JSON.writeValueAsBytes(event);

        assertThatThrownBy(() -> consumer.toCommand(body, LedgerOperation.SEIZE))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @ParameterizedTest(name = "seize amount {0}")
    @CsvSource({"0.00", "-5.00", "100.5"})
    void seizeAmountMustBePositiveMoney(String amount) throws Exception {
        Map<String, Object> event = seizeRequest("CONTRACTUAL_PENALTY", "MILESTONE_PAYMENT");
        payload(event).put("amount", amount);
        byte[] body = JSON.writeValueAsBytes(event);

        assertThatThrownBy(() -> consumer.toCommand(body, LedgerOperation.SEIZE))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    void theSeizeQueueRefusesOtherEventsAndEntryTypes() throws Exception {
        Map<String, Object> wrongEvent = seizeRequest("CONTRACTUAL_PENALTY", "MILESTONE_PAYMENT");
        wrongEvent.put("eventType", "bank.release_requested");
        Map<String, Object> refundType = seizeRequest("CONTRACTUAL_PENALTY", "MILESTONE_PAYMENT");
        payload(refundType).put("entryType", "REFUND_TO_BUYER");

        assertThatThrownBy(() -> consumer.toCommand(JSON.writeValueAsBytes(wrongEvent), LedgerOperation.SEIZE))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> consumer.toCommand(JSON.writeValueAsBytes(refundType), LedgerOperation.SEIZE))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    void aQueueRefusesAnotherOperationsEventOrEntryType() throws Exception {
        byte[] release = JSON.writeValueAsBytes(request("bank.release_requested", "RELEASE_TO_SELLER", "SELLER_DEPOSIT"));
        byte[] refundTypedAsRelease =
                JSON.writeValueAsBytes(request("bank.release_requested", "REFUND_TO_BUYER", "BUYER_DEPOSIT"));

        assertThatThrownBy(() -> consumer.toCommand(release, LedgerOperation.REFUND))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> consumer.toCommand(release, LedgerOperation.LOCK))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> consumer.toCommand(refundTypedAsRelease, LedgerOperation.RELEASE))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    /** Every way a message can fail the frozen schema or the bank command rules ends as a DLQ-bound rejection. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenRequests")
    void rejectsWhatTheContractDoesNotAllow(String why, Consumer<Map<String, Object>> breakIt) throws Exception {
        Map<String, Object> event = validRequest();
        breakIt.accept(event);
        byte[] body = JSON.writeValueAsBytes(event);

        assertThatThrownBy(() -> consumer.toCommand(body, LedgerOperation.LOCK))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @SuppressWarnings("unchecked")
    static Stream<Arguments> brokenRequests() {
        return Stream.of(
                Arguments.of("sourceEventId differs from eventId",
                        (Consumer<Map<String, Object>>) e -> payload(e).put("sourceEventId", id())),
                Arguments.of("missing correlationId", (Consumer<Map<String, Object>>) e -> e.remove("correlationId")),
                Arguments.of("not a lock", (Consumer<Map<String, Object>>) e -> payload(e).put("entryType", "RELEASE_TO_SELLER")),
                Arguments.of("amount with one decimal", (Consumer<Map<String, Object>>) e -> payload(e).put("amount", "100.5")),
                Arguments.of("amount as a number", (Consumer<Map<String, Object>>) e -> payload(e).put("amount", 100)),
                Arguments.of("zero amount", (Consumer<Map<String, Object>>) e -> payload(e).put("amount", "0.00")),
                Arguments.of("required nullable key absent", (Consumer<Map<String, Object>>) e -> payload(e).remove("remedyLegId")),
                Arguments.of("unknown payload field", (Consumer<Map<String, Object>>) e -> payload(e).put("note", "x")),
                Arguments.of("wrong producer", (Consumer<Map<String, Object>>) e -> e.put("producer", "contract-service")),
                Arguments.of("not JSON at all", (Consumer<Map<String, Object>>) e -> e.put("eventVersion", "one")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    static Map<String, Object> request(String eventType, String entryType, String fundType) {
        Map<String, Object> event = validRequest();
        event.put("eventType", eventType);
        payload(event).put("entryType", entryType);
        payload(event).put("fundType", fundType);
        if ("MILESTONE_PAYMENT".equals(fundType)) {
            payload(event).put("milestoneId", id());
        }
        return event;
    }

    /** bank.seize_requested: a remedy leg, so decision and leg ids are always set. */
    static Map<String, Object> seizeRequest(String entryType, String fundType) {
        Map<String, Object> event = request("bank.seize_requested", entryType, fundType);
        payload(event).put("remedyDecisionId", id());
        payload(event).put("remedyLegId", id());
        return event;
    }

    static Map<String, Object> validRequest() {
        String eventId = id();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", id());
        payload.put("milestoneId", null);
        payload.put("userId", id());
        payload.put("entryType", "LOCK_BUYER_DEPOSIT");
        payload.put("fundType", "BUYER_DEPOSIT");
        payload.put("remedyDecisionId", null);
        payload.put("remedyLegId", null);
        payload.put("amount", "40000000.00");
        payload.put("sourceEventId", eventId);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "bank.lock_requested");
        event.put("eventVersion", 1);
        event.put("occurredAt", "2026-10-27T03:00:00Z");
        event.put("producer", "escrow-service");
        event.put("aggregateId", payload.get("contractId"));
        event.put("correlationId", id());
        event.put("payload", payload);
        return event;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
