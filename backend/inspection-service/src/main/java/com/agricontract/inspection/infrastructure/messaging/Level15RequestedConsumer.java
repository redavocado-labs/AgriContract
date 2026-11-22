package com.agricontract.inspection.infrastructure.messaging;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.InvalidEnvelopeException;
import com.agricontract.inspection.application.usecase.ManageLevel15Assignments;
import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.application.dto.Level15AssignmentRequestedCommand;
import com.agricontract.inspection.application.exception.InvalidEventPayloadException;
import com.agricontract.eventing.inbox.ProcessedEventStore;
import com.agricontract.inspection.infrastructure.config.AssignmentRabbitConfiguration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Level15RequestedConsumer {
    private static final String CONSUMER_NAME = "inspection.level1_5_requested";
    private static final Set<String> FIELDS = Set.of("contractId", "milestoneId", "reason",
            "reportDueBusinessDays", "initiatedByActorId", "requestedAt");
    private static final Set<String> REASONS = Set.of("DISPUTE_ROUTED", "MANDATORY_INSPECTION",
            "FORCE_MAJEURE_CONTEST", "DECISION_CONTESTED", "LEVEL2_BUFFER_EXPIRED");
    private final EventEnvelopeCodec codec;
    private final ManageLevel15Assignments assignments;
    private final ProcessedEventStore processedEvents;
    private final TransactionRunner transactions;

    @RabbitListener(queues = AssignmentRabbitConfiguration.QUEUE)
    public void accept(Message message) {
        EventEnvelope event;
        Level15AssignmentRequestedCommand command = null;
        String contract = null;
        String milestone = null;
        java.time.Instant cancelledAt = null;
        try {
            event = codec.parse(message.getBody());
            if (!"contract-service".equals(event.producer()) || event.eventVersion() != 1)
                throw new IllegalArgumentException("Unexpected assignment event");
            if (AssignmentRabbitConfiguration.KEY.equals(event.eventType())) {
                command = toCommand(event);
            } else if (AssignmentRabbitConfiguration.CANCEL_KEY.equals(event.eventType())) {
                var payload = event.payload();
                if (!payload.keySet().equals(Set.of("contractId", "milestoneId", "reason", "cancelledAt"))
                        || !Set.of("DISPUTE_CONCEDED", "DISPUTE_SETTLED").contains(text(payload.get("reason"))))
                    throw new IllegalArgumentException("Invalid cancellation payload");
                contract = id(payload.get("contractId"));
                milestone = id(payload.get("milestoneId"));
                cancelledAt = OffsetDateTime.parse(text(payload.get("cancelledAt"))).toInstant();
            } else throw new IllegalArgumentException("Unexpected assignment event");
        } catch (InvalidEnvelopeException | IllegalArgumentException | DateTimeException ex) {
            throw new InvalidEventPayloadException("Invalid Level 1.5 request; inspect the restricted DLQ");
        }
        // Dependency/database failures remain retryable and roll back the processed-event claim.
        var request = command;
        var cancelledContract = contract;
        var cancelledMilestone = milestone;
        var timestamp = cancelledAt;
        String correlation = event.correlationId();
        try { id(correlation); } catch (IllegalArgumentException invalid) { correlation = event.eventId().toString(); }
        var safeCorrelation = correlation;
        transactions.inReadCommittedTransaction(() -> {
            if (!processedEvents.markProcessed(CONSUMER_NAME, event)) return;
            if (request != null) assignments.requested(request);
            else assignments.cancel(cancelledContract, cancelledMilestone, timestamp, safeCorrelation, event.eventId().toString());
        });
    }

    private Level15AssignmentRequestedCommand toCommand(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        if (!payload.keySet().equals(FIELDS)
                || !(payload.get("reportDueBusinessDays") instanceof Integer days)
                || days < 1 || days > 30 || !REASONS.contains(text(payload.get("reason"))))
            throw new IllegalArgumentException("Invalid assignment payload");
        OffsetDateTime.parse(text(payload.get("requestedAt")));
        String correlationId = event.correlationId();
        try {
            id(correlationId);
        } catch (IllegalArgumentException invalidCorrelation) {
            correlationId = event.eventId().toString();
        }
        return new Level15AssignmentRequestedCommand(id(payload.get("contractId")),
                id(payload.get("milestoneId")), days,
                payload.get("initiatedByActorId") == null ? null : id(payload.get("initiatedByActorId")),
                event.eventId().toString(), correlationId);
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Invalid field");
        return text;
    }

    private static String id(Object value) {
        String text = text(value);
        String canonical = UUID.fromString(text).toString();
        if (!canonical.equalsIgnoreCase(text)) throw new IllegalArgumentException("Invalid UUID");
        return canonical;
    }
}
