package com.agricontract.escrow.infrastructure.messaging;

import com.agricontract.escrow.application.dto.MilestoneFundingRequestedCommand;
import com.agricontract.escrow.application.exception.InvalidEventPayloadException;
import com.agricontract.escrow.application.usecase.RequestMilestoneFundingUseCase;
import com.agricontract.escrow.infrastructure.config.RabbitMQConfig;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.InvalidEnvelopeException;
import com.agricontract.eventing.inbox.ProcessedEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * milestone.funding_requested (golden-flow-events.yaml milestoneFundingRequested) → lock the batch (#120). A payload
 * outside the schema is an InvalidEventPayloadException: DLQ, no retry. The eventId is claimed and the funding row +
 * bank.lock_requested written in one transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneFundingRequestedConsumer {

    static final String CONSUMER_NAME = "escrow-service.milestone-funding-requested";
    static final String EVENT_TYPE = "milestone.funding_requested";
    static final String PRODUCER = "contract-service";

    // Every field is required and nothing else is allowed (additionalProperties: false).
    private static final Set<String> FIELDS = Set.of("contractId", "milestoneId", "milestoneIndex", "buyerId",
            "batchAmount", "requestedAt");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    // positiveMoney rounded to the whole dong (D65): "^[0-9]+\.00$" and above zero.
    private static final Pattern WHOLE_DONG = Pattern.compile("^[1-9][0-9]*\\.00$");

    private final EventEnvelopeCodec codec;
    private final ProcessedEventStore processedEvents;
    private final RequestMilestoneFundingUseCase requestMilestoneFunding;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitMQConfig.MILESTONE_FUNDING_REQUESTED_QUEUE)
    public void onFundingRequested(Message message) {
        EventEnvelope envelope = parse(message.getBody());
        MilestoneFundingRequestedCommand command = toCommand(envelope);
        transactionTemplate.executeWithoutResult(status -> {
            if (!processedEvents.markProcessed(CONSUMER_NAME, envelope)) {
                log.info("event=milestone_funding_requested_duplicate eventId={} milestoneId={}",
                        envelope.eventId(), command.milestoneId());
                return;
            }
            requestMilestoneFunding.execute(command);
        });
    }

    private EventEnvelope parse(byte[] body) {
        try {
            return codec.parse(body);
        } catch (InvalidEnvelopeException | IllegalArgumentException e) {
            throw new InvalidEventPayloadException("Malformed event envelope");
        }
    }

    MilestoneFundingRequestedCommand toCommand(EventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType()) || !PRODUCER.equals(envelope.producer())
                || envelope.eventVersion() != 1) {
            throw new InvalidEventPayloadException("Unexpected event type, producer or version");
        }
        Map<String, Object> payload = envelope.payload();
        if (!payload.keySet().equals(FIELDS)) {
            throw new InvalidEventPayloadException("Payload fields do not match milestone.funding_requested");
        }
        String eventId = envelope.eventId().toString();
        return new MilestoneFundingRequestedCommand(
                eventId,
                envelope.correlationId() != null ? envelope.correlationId() : eventId,
                uuid(payload, "contractId"),
                uuid(payload, "milestoneId"),
                milestoneIndex(payload),
                uuid(payload, "buyerId"),
                batchAmount(payload),
                requestedAt(payload));
    }

    private static String uuid(Map<String, Object> payload, String field) {
        if (!(payload.get(field) instanceof String text) || !UUID.matcher(text).matches()) {
            throw new InvalidEventPayloadException(field + " is not a UUID");
        }
        return text.toLowerCase();
    }

    private static int milestoneIndex(Map<String, Object> payload) {
        Object value = payload.get("milestoneIndex");
        if (!(value instanceof Integer index) || index < 1 || index > 100) {
            throw new InvalidEventPayloadException("milestoneIndex is not an integer 1..100");
        }
        return index;
    }

    private static BigDecimal batchAmount(Map<String, Object> payload) {
        if (!(payload.get("batchAmount") instanceof String text) || !WHOLE_DONG.matcher(text).matches()) {
            throw new InvalidEventPayloadException("batchAmount is not a positive whole-dong money string");
        }
        return new BigDecimal(text);
    }

    private static Instant requestedAt(Map<String, Object> payload) {
        if (!(payload.get("requestedAt") instanceof String text)) {
            throw new InvalidEventPayloadException("requestedAt is not a date-time");
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidEventPayloadException("requestedAt is not a date-time");
        }
    }
}
