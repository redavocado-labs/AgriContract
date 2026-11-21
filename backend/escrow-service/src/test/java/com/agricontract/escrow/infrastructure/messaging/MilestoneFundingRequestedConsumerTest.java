package com.agricontract.escrow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agricontract.escrow.application.dto.MilestoneFundingRequestedCommand;
import com.agricontract.escrow.application.exception.InvalidEventPayloadException;
import com.agricontract.escrow.application.usecase.RequestMilestoneFundingUseCase;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.inbox.ProcessedEventStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** Reading milestone.funding_requested against the frozen schema rules (#120). */
class MilestoneFundingRequestedConsumerTest {

    private final MilestoneFundingRequestedConsumer consumer = new MilestoneFundingRequestedConsumer(
            new EventEnvelopeCodec(), mock(ProcessedEventStore.class), mock(RequestMilestoneFundingUseCase.class),
            mock(TransactionTemplate.class));

    @Test
    void validEvent_becomesACommand() {
        EventEnvelope envelope = envelope(validPayload());

        MilestoneFundingRequestedCommand command = consumer.toCommand(envelope);

        assertThat(command.eventId()).isEqualTo(envelope.eventId().toString());
        assertThat(command.correlationId()).isEqualTo(envelope.correlationId());
        assertThat(command.milestoneIndex()).isEqualTo(2);
        assertThat(command.batchAmount()).isEqualByComparingTo(new BigDecimal("300000000.00"));
        assertThat(command.requestedAt()).isEqualTo(Instant.parse("2026-11-20T01:00:00Z"));
    }

    @Test
    void missingCorrelation_startsTheChainAtThisEvent() {
        Map<String, Object> payload = validPayload();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "milestone.funding_requested", 1, Instant.now(),
                "contract-service", (String) payload.get("contractId"), null, null, null, payload);

        assertThat(consumer.toCommand(envelope).correlationId()).isEqualTo(envelope.eventId().toString());
    }

    @Test
    void payloadOutsideTheSchema_isRefused() {
        Map<String, Object> extra = validPayload();
        extra.put("note", "x");
        Map<String, Object> missing = validPayload();
        missing.remove("buyerId");
        Map<String, Object> notRounded = validPayload();
        notRounded.put("batchAmount", "300000000.50");
        Map<String, Object> zero = validPayload();
        zero.put("batchAmount", "0.00");
        Map<String, Object> number = validPayload();
        number.put("batchAmount", 300000000);
        Map<String, Object> index = validPayload();
        index.put("milestoneIndex", 0);
        Map<String, Object> indexText = validPayload();
        indexText.put("milestoneIndex", "2");
        Map<String, Object> notAUuid = validPayload();
        notAUuid.put("milestoneId", "m-1");
        Map<String, Object> badTime = validPayload();
        badTime.put("requestedAt", "yesterday");

        for (Map<String, Object> payload : new Map[]{extra, missing, notRounded, zero, number, index, indexText,
                notAUuid, badTime}) {
            assertThatThrownBy(() -> consumer.toCommand(envelope(payload)))
                    .isInstanceOf(InvalidEventPayloadException.class);
        }
    }

    @Test
    void wrongProducer_isRefused() {
        Map<String, Object> payload = validPayload();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "milestone.funding_requested", 1, Instant.now(),
                "escrow-service", (String) payload.get("contractId"), null, null, null, payload);

        assertThatThrownBy(() -> consumer.toCommand(envelope)).isInstanceOf(InvalidEventPayloadException.class);
    }

    static Map<String, Object> validPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", UUID.randomUUID().toString());
        payload.put("milestoneId", UUID.randomUUID().toString());
        payload.put("milestoneIndex", 2);
        payload.put("buyerId", UUID.randomUUID().toString());
        payload.put("batchAmount", "300000000.00");
        payload.put("requestedAt", "2026-11-20T01:00:00Z");
        return payload;
    }

    private static EventEnvelope envelope(Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), "milestone.funding_requested", 1, Instant.now(),
                "contract-service", String.valueOf(payload.get("contractId")), UUID.randomUUID().toString(), null,
                null, payload);
    }
}
