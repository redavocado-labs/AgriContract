package com.agricontract.escrow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agricontract.escrow.application.dto.RemedyFinalizedCommand;
import com.agricontract.escrow.application.exception.InvalidEventPayloadException;
import com.agricontract.escrow.application.usecase.DispatchRemedyLegsUseCase;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.inbox.ProcessedEventStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** Reading remedy.finalized against the frozen schema shape (#249). */
class RemedyFinalizedConsumerTest {

    private final RemedyFinalizedConsumer consumer = new RemedyFinalizedConsumer(new EventEnvelopeCodec(),
            mock(ProcessedEventStore.class), mock(DispatchRemedyLegsUseCase.class), mock(TransactionTemplate.class));

    @Test
    void validEvent_becomesACommand() {
        RemedyFinalizedCommand command = consumer.toCommand(envelope(validPayload()));

        assertThat(command.legs()).hasSize(1);
        assertThat(command.legs().get(0).amount()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(command.legs().get(0).role()).isEqualTo("BUYER");
        assertThat(command.affectedMilestoneIds()).hasSize(1);
    }

    @Test
    void unknownOrMissingKey_isInvalid() {
        Map<String, Object> extra = validPayload();
        extra.put("surprise", 1);
        Map<String, Object> missing = validPayload();
        missing.remove("reputationEligible");

        assertThatThrownBy(() -> consumer.toCommand(envelope(extra))).isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> consumer.toCommand(envelope(missing))).isInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    void legWithAnUnknownKeyEnumOrBadAmount_isInvalid() {
        for (Map<String, Object> bad : List.of(
                leg("remedyType", "SEIZE"), leg("role", "ADMIN"), leg("fundType", "WALLET"),
                leg("amount", "12.5"), leg("amount", "-1.00"), leg("remedyLegId", "nope"), leg("extra", "x"))) {
            Map<String, Object> payload = validPayload();
            payload.put("remedyLegs", List.of(bad));
            assertThatThrownBy(() -> consumer.toCommand(envelope(payload)))
                    .isInstanceOf(InvalidEventPayloadException.class);
        }
    }

    @Test
    void wrongProducerOrNotUniqueMilestones_isInvalid() {
        Map<String, Object> payload = validPayload();
        String m = UUID.randomUUID().toString();
        payload.put("affectedMilestoneIds", List.of(m, m));

        assertThatThrownBy(() -> consumer.toCommand(envelope(payload))).isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> consumer.toCommand(new EventEnvelope(UUID.randomUUID(), "remedy.finalized", 1,
                Instant.now(), "bank-service", "c", null, null, null, validPayload())))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    private static Map<String, Object> leg(String key, Object value) {
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("remedyLegId", UUID.randomUUID().toString());
        leg.put("remedyType", "PAYMENT_REFUND");
        leg.put("fundType", "BUYER_DEPOSIT");
        leg.put("role", "BUYER");
        leg.put("amount", "12.50");
        leg.put(key, value);
        return leg;
    }

    private static Map<String, Object> validPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("remedyDecisionId", UUID.randomUUID().toString());
        payload.put("attributionDecisionId", UUID.randomUUID().toString());
        payload.put("breachCaseId", null);
        payload.put("contractId", UUID.randomUUID().toString());
        payload.put("buyerId", UUID.randomUUID().toString());
        payload.put("sellerId", UUID.randomUUID().toString());
        payload.put("affectedMilestoneIds", List.of(UUID.randomUUID().toString()));
        payload.put("settledMilestoneCount", 0);
        payload.put("finalBreachingRole", null);
        payload.put("breachReasonCode", null);
        payload.put("qualityDisposition", null);
        payload.put("decisionSource", "SYSTEM");
        payload.put("penaltyEligible", false);
        payload.put("reputationEligible", false);
        payload.put("remedyLegs", List.of(leg("amount", "12.50")));
        return payload;
    }

    private static EventEnvelope envelope(Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), "remedy.finalized", 1, Instant.now(), "contract-service",
                (String) payload.get("contractId"), UUID.randomUUID().toString(), null, null, payload);
    }
}
