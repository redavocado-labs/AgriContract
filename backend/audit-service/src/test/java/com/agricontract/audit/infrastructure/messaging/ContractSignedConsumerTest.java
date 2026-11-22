package com.agricontract.audit.infrastructure.messaging;

import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractSignedConsumerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContractSignedConsumer consumer = new ContractSignedConsumer(new EventEnvelopeCodec(), command -> { });

    @Test
    void readsAValidEventWithEveryTypedField() throws Exception {
        Map<String, Object> event = validEvent();
        event.put("causationId", "cause-1");
        AuditIngestCommand command = consumer.toCommand(JSON.writeValueAsBytes(event));

        Map<String, Object> payload = payload(event);
        assertThat(command.subjectType()).isEqualTo(SubjectType.CONTRACT);
        assertThat(command.subjectId()).isEqualTo(payload.get("contractId"));
        assertThat(command.sourceType()).isEqualTo("CONTRACT_SIGNED");
        assertThat(command.sourceEventType()).isEqualTo("contract.signed");
        assertThat(command.sourceEventId()).isEqualTo(event.get("eventId"));
        assertThat(command.causationId()).isEqualTo("cause-1");
        assertThat(command.correlationId()).isEqualTo(event.get("correlationId"));
        assertThat(command.sourceHash()).isEqualTo(payload.get("signedContentHash"));
        assertThat(command.occurredAt()).isEqualTo(Instant.parse("2026-10-27T03:00:00Z"));
        assertThat(command.content().keySet()).containsExactlyElementsOf(ContractSignedConsumer.PAYLOAD_KEYS);
    }

    @Test
    void keepsMoneyAndQuantityAsExactText() throws Exception {
        AuditIngestCommand command = consumer.toCommand(JSON.writeValueAsBytes(validEvent()));

        assertThat(command.content()).containsEntry("totalAmount", "1500000.00")
                .containsEntry("buyerDepositAmount", "300000.00")
                .containsEntry("sellerDepositAmount", "150000.00")
                .containsEntry("reservedQuantity", "1250.50")
                .containsEntry("commodity", "COFFEE");
    }

    @Test
    void integerQuantityStaysAnInteger() throws Exception {
        Map<String, Object> event = validEvent();
        payload(event).put("reservedQuantity", 1000);
        assertThat(consumer.toCommand(JSON.writeValueAsBytes(event)).content()).containsEntry("reservedQuantity", "1000");
    }

    @Test
    void doesNotCopyUnknownPayloadKeys() throws Exception {
        Map<String, Object> event = validEvent();
        payload(event).put("extra", "x");
        assertThat(consumer.toCommand(JSON.writeValueAsBytes(event)).content()).doesNotContainKey("extra");
    }

    @Test
    void acceptsAMatchingEnvelopeSubject() throws Exception {
        Map<String, Object> event = validEvent();
        event.put("subject", Map.of("type", "CONTRACT", "id", payload(event).get("contractId")));
        assertThat(consumer.toCommand(JSON.writeValueAsBytes(event)).subjectId()).isEqualTo(payload(event).get("contractId"));
    }

    @Test
    void rejectsASubjectThatDoesNotMatch() {
        assertInvalid(e -> e.put("subject", Map.of("type", "CONTRACT", "id", id())));
        assertInvalid(e -> e.put("subject", Map.of("type", "SYSTEM", "id", payload(e).get("contractId"))));
    }

    @Test
    void rejectsAMissingPayloadKey() {
        for (String key : ContractSignedConsumer.PAYLOAD_KEYS) {
            assertInvalid(e -> payload(e).remove(key));
        }
    }

    @Test
    void rejectsABadHash() {
        assertInvalid(e -> payload(e).put("signedContentHash", "ABC"));
        assertInvalid(e -> payload(e).put("signedContentHash", "A".repeat(64)));
    }

    @Test
    void rejectsAWrongEventType() {
        assertInvalid(e -> e.put("eventType", "contract.terminated"));
    }

    @Test
    void rejectsABadContractId() {
        assertInvalid(e -> payload(e).put("contractId", "not-a-uuid"));
    }

    @Test
    void rejectsAMalformedEnvelope() {
        assertThatThrownBy(() -> consumer.toCommand("{not json".getBytes())).isInstanceOf(InvalidEventPayloadException.class);
        assertInvalid(e -> e.remove("eventId"));
    }

    private void assertInvalid(Consumer<Map<String, Object>> mutation) {
        Map<String, Object> event = validEvent();
        mutation.accept(event);
        assertThatThrownBy(() -> consumer.toCommand(JSON.writeValueAsBytes(event)))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static Map<String, Object> validEvent() {
        String contractId = id();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("listingId", id());
        payload.put("reservedQuantity", new java.math.BigDecimal("1250.50"));
        payload.put("commodity", "COFFEE");
        payload.put("buyerId", id());
        payload.put("sellerId", id());
        payload.put("totalAmount", "1500000.00");
        payload.put("buyerDepositAmount", "300000.00");
        payload.put("sellerDepositAmount", "150000.00");
        payload.put("signedContentHash", "a".repeat(32) + "0123456789".repeat(3) + "ab");
        payload.put("signedAt", "2026-10-27T02:59:00Z");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", id());
        event.put("eventType", "contract.signed");
        event.put("eventVersion", 1);
        event.put("occurredAt", "2026-10-27T03:00:00Z");
        event.put("producer", "contract-service");
        event.put("aggregateId", contractId);
        event.put("correlationId", id());
        event.put("payload", payload);
        return event;
    }
}
