package com.agricontract.escrow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agricontract.escrow.application.dto.MilestoneSettledCommand;
import com.agricontract.escrow.application.exception.InvalidEventPayloadException;
import com.agricontract.escrow.application.usecase.SettleMilestoneUseCase;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.inbox.ProcessedEventStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** Reading milestone.settled against the frozen schema rules (#248). */
class MilestoneSettledConsumerTest {

    static final String SELLER = UUID.randomUUID().toString();
    static final String BUYER = UUID.randomUUID().toString();

    private final MilestoneSettledConsumer consumer = new MilestoneSettledConsumer(new EventEnvelopeCodec(),
            mock(ProcessedEventStore.class), mock(SettleMilestoneUseCase.class), mock(TransactionTemplate.class));

    @Test
    void validEvent_becomesACommand_withThePayeesFromRecipients() {
        EventEnvelope envelope = envelope(validPayload());

        MilestoneSettledCommand command = consumer.toCommand(envelope);

        assertThat(command.eventId()).isEqualTo(envelope.eventId().toString());
        assertThat(command.correlationId()).isEqualTo(envelope.correlationId());
        assertThat(command.lockedAmount()).isEqualByComparingTo(new BigDecimal("300000000.00"));
        assertThat(command.actualAmount()).isEqualByComparingTo(new BigDecimal("292500000.00"));
        assertThat(command.sellerId()).isEqualTo(SELLER);
        assertThat(command.buyerId()).isEqualTo(BUYER);
    }

    @Test
    void zeroActual_andNoRecipients_areValid() {
        Map<String, Object> payload = validPayload();
        payload.put("actualAmount", "0.00");
        payload.put("recipients", List.of());

        MilestoneSettledCommand command = consumer.toCommand(envelope(payload));

        assertThat(command.actualAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(command.sellerId()).isNull();
        assertThat(command.buyerId()).isNull();
    }

    @Test
    void missingCorrelation_startsTheChainAtThisEvent() {
        Map<String, Object> payload = validPayload();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "milestone.settled", 1, Instant.now(),
                "contract-service", (String) payload.get("contractId"), null, null, null, payload);

        assertThat(consumer.toCommand(envelope).correlationId()).isEqualTo(envelope.eventId().toString());
    }

    @Test
    void payloadOutsideTheSchema_isRefused() {
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(with("note", "x"));
        bad.add(without("recipients"));
        bad.add(with("lockedAmount", "0.00"));
        bad.add(with("lockedAmount", "300000000.50"));
        bad.add(with("actualAmount", "292500000.5"));
        bad.add(with("actualAmount", "-1.00"));
        bad.add(with("actualAmount", 292500000));
        bad.add(with("milestoneId", "m-1"));
        bad.add(with("recipients", "seller"));
        bad.add(with("recipients", List.of(recipient(SELLER, "SELLER"), recipient(UUID.randomUUID().toString(),
                "SELLER"))));
        bad.add(with("recipients", List.of(recipient(SELLER, "ARBITRATOR"))));
        bad.add(with("recipients", List.of(Map.of("userId", SELLER, "role", "SELLER"))));
        Map<String, Object> noEmail = new LinkedHashMap<>(recipient(SELLER, "SELLER"));
        noEmail.put("email", "not-an-email");
        bad.add(with("recipients", List.of(noEmail)));

        for (Map<String, Object> payload : bad) {
            assertThatThrownBy(() -> consumer.toCommand(envelope(payload)))
                    .as(payload.toString()).isInstanceOf(InvalidEventPayloadException.class);
        }
    }

    @Test
    void wrongProducer_isRefused() {
        Map<String, Object> payload = validPayload();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "milestone.settled", 1, Instant.now(),
                "escrow-service", (String) payload.get("contractId"), null, null, null, payload);

        assertThatThrownBy(() -> consumer.toCommand(envelope)).isInstanceOf(InvalidEventPayloadException.class);
    }

    static Map<String, Object> validPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", UUID.randomUUID().toString());
        payload.put("milestoneId", UUID.randomUUID().toString());
        payload.put("lockedAmount", "300000000.00");
        payload.put("actualAmount", "292500000.00");
        payload.put("recipients", List.of(recipient(BUYER, "BUYER"), recipient(SELLER, "SELLER")));
        return payload;
    }

    static Map<String, Object> recipient(String userId, String role) {
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("userId", userId);
        recipient.put("email", role.toLowerCase() + "@example.com");
        recipient.put("role", role);
        return recipient;
    }

    private static Map<String, Object> with(String field, Object value) {
        Map<String, Object> payload = validPayload();
        payload.put(field, value);
        return payload;
    }

    private static Map<String, Object> without(String field) {
        Map<String, Object> payload = validPayload();
        payload.remove(field);
        return payload;
    }

    private static EventEnvelope envelope(Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), "milestone.settled", 1, Instant.now(), "contract-service",
                String.valueOf(payload.get("contractId")), UUID.randomUUID().toString(), null, null, payload);
    }
}
