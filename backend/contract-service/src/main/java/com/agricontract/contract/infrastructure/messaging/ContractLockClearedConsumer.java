package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.dto.ContractLockClearedCommand;
import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.agricontract.contract.application.usecase.CommitTerminalAfterLockClearedUseCase;
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

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * escrow.contract_lock_cleared (golden-flow-events.yaml escrowContractLockCleared, D93) →
 * CommitTerminalAfterLockClearedUseCase. Parsed against the schema (DLQ, no retry); claimed and applied in one
 * transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractLockClearedConsumer {
    static final String CONSUMER_NAME = "contract-service.contract-lock-cleared";
    static final String EVENT_TYPE = "escrow.contract_lock_cleared";
    static final String PRODUCER = "escrow-service";

    private static final Set<String> FIELDS = Set.of("contractId", "remedyDecisionIds", "clearedAt");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final EventEnvelopeCodec codec;
    private final ProcessedEventStore processedEvents;
    private final CommitTerminalAfterLockClearedUseCase commitTerminal;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitMQConfig.CONTRACT_LOCK_CLEARED_QUEUE)
    public void onLockCleared(Message message) {
        EventEnvelope envelope = parse(message.getBody());
        String eventId = envelope.eventId().toString();
        String correlationId = envelope.correlationId() != null ? envelope.correlationId() : eventId;
        ContractLockClearedCommand command = command(envelope, eventId, correlationId);
        transactionTemplate.executeWithoutResult(status -> {
            if (!processedEvents.markProcessed(CONSUMER_NAME, envelope)) {
                log.info("event=contract_lock_cleared_duplicate eventId={} contractId={}", eventId,
                        command.contractId());
                return;
            }
            commitTerminal.execute(command);
        });
    }

    private EventEnvelope parse(byte[] body) {
        try {
            return codec.parse(body);
        } catch (InvalidEnvelopeException | IllegalArgumentException e) {
            throw new InvalidEventPayloadException("Malformed event envelope", e);
        }
    }

    ContractLockClearedCommand command(EventEnvelope envelope, String eventId, String correlationId) {
        if (!EVENT_TYPE.equals(envelope.eventType()) || !PRODUCER.equals(envelope.producer())
                || envelope.eventVersion() != 1) {
            throw new InvalidEventPayloadException("Unexpected event type, producer or version", null);
        }
        Map<String, Object> payload = envelope.payload();
        if (!payload.keySet().equals(FIELDS)) {
            throw new InvalidEventPayloadException("Payload fields do not match " + EVENT_TYPE, null);
        }
        if (!(payload.get("contractId") instanceof String contractId) || !UUID.matcher(contractId).matches()) {
            throw new InvalidEventPayloadException("contractId is not a UUID", null);
        }
        if (!(payload.get("remedyDecisionIds") instanceof List<?> ids) || ids.isEmpty()) {
            throw new InvalidEventPayloadException("remedyDecisionIds must be a non-empty list", null);
        }
        List<String> decisionIds = new ArrayList<>(ids.size());
        Set<String> seen = new HashSet<>();
        for (Object id : ids) {
            if (!(id instanceof String text) || !UUID.matcher(text).matches()) {
                throw new InvalidEventPayloadException("remedyDecisionIds holds a value that is not a UUID", null);
            }
            String normalized = text.toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                throw new InvalidEventPayloadException("remedyDecisionIds holds a duplicate", null);
            }
            decisionIds.add(normalized);
        }
        if (!(payload.get("clearedAt") instanceof String clearedAt)) {
            throw new InvalidEventPayloadException("clearedAt is required", null);
        }
        try {
            OffsetDateTime.parse(clearedAt);
        } catch (DateTimeParseException e) {
            throw new InvalidEventPayloadException("clearedAt is not a date-time", e);
        }
        return new ContractLockClearedCommand(contractId.toLowerCase(Locale.ROOT), decisionIds, eventId,
                correlationId);
    }
}
