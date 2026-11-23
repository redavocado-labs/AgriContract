package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.dto.Level15AssignedCommand;
import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.agricontract.contract.application.usecase.RecordLevel15AssignmentUseCase;
import com.agricontract.contract.infrastructure.config.RabbitMQConfig;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * inspection.level1_5_assigned (golden-flow-events.yaml inspectionLevel15Assigned, D57) →
 * RecordLevel15AssignmentUseCase. The envelope's payload (a Map) is parsed here by hand against the schema: a payload
 * that does not fit is an InvalidEventPayloadException (DLQ, no retry). The eventId is claimed in processed_event and
 * the assignment stored in one transaction, so a redelivery is a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Level15AssignedConsumer {
    static final String CONSUMER = "contract-service.inspection-level1-5-assigned";
    static final String EVENT_TYPE = "inspection.level1_5_assigned";
    static final String PRODUCER = "inspection-service";

    private static final Set<String> FIELDS = Set.of("contractId", "milestoneId", "commissionId", "assignedAt",
            "freeCancelUntil", "reportDueAt");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final EventEnvelopeCodec codec;
    private final ProcessedEventStore processedEvents;
    private final RecordLevel15AssignmentUseCase recordAssignment;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitMQConfig.INSPECTION_LEVEL1_5_ASSIGNED_QUEUE)
    public void onAssigned(Message message) {
        EventEnvelope envelope = parse(message.getBody());
        Level15AssignedCommand command = toCommand(envelope);
        transactionTemplate.executeWithoutResult(status -> {
            if (processedEvents.markProcessed(CONSUMER, envelope)) {
                recordAssignment.execute(command);
            } else {
                log.info("event=level1_5_assigned_duplicate eventId={}", envelope.eventId());
            }
        });
    }

    private EventEnvelope parse(byte[] body) {
        try {
            return codec.parse(body);
        } catch (InvalidEnvelopeException | IllegalArgumentException e) {
            throw new InvalidEventPayloadException("Malformed event envelope", e);
        }
    }

    Level15AssignedCommand toCommand(EventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType()) || !PRODUCER.equals(envelope.producer())
                || envelope.eventVersion() != 1) {
            throw new InvalidEventPayloadException("Unexpected event type, producer or version", null);
        }
        Map<String, Object> payload = envelope.payload();
        if (payload == null || !payload.keySet().equals(FIELDS)) {
            throw new InvalidEventPayloadException("payload fields do not match " + EVENT_TYPE, null);
        }
        Instant assignedAt = instant(payload, "assignedAt");
        Instant freeCancelUntil = instant(payload, "freeCancelUntil");
        Instant reportDueAt = instant(payload, "reportDueAt");
        if (freeCancelUntil.isBefore(assignedAt) || reportDueAt.isBefore(assignedAt)) {
            throw new InvalidEventPayloadException("freeCancelUntil and reportDueAt cannot precede assignedAt", null);
        }
        return new Level15AssignedCommand(uuid(payload, "contractId"), uuid(payload, "milestoneId"),
                uuid(payload, "commissionId"), assignedAt, freeCancelUntil, reportDueAt,
                envelope.eventId().toString());
    }

    private static String uuid(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String value) || !UUID.matcher(value).matches()) {
            throw new InvalidEventPayloadException(key + " is not a UUID", null);
        }
        return value.toLowerCase();
    }

    private static Instant instant(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String value)) {
            throw new InvalidEventPayloadException(key + " is not a date-time", null);
        }
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new InvalidEventPayloadException(key + " is not a date-time", e);
        }
    }
}
