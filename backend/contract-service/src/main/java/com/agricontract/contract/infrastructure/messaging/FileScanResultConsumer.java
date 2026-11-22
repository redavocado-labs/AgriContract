package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.agricontract.contract.application.usecase.RecordFileScanResultUseCase;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
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

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * file.ready and file.failed (golden-flow-events.yaml fileReady, fileFailed; D71) → RecordFileScanResultUseCase.
 * Parsed here against the schema (InvalidEventPayloadException: DLQ, no retry); the eventId is claimed and the outcome
 * kept in one transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileScanResultConsumer {
    static final String READY_CONSUMER = "contract-service.file-ready";
    static final String FAILED_CONSUMER = "contract-service.file-failed";
    static final String READY = "file.ready";
    static final String FAILED = "file.failed";
    static final String PRODUCER = "file-service";

    private static final Set<String> READY_REQUIRED = Set.of("fileId", "uploadedBy", "ingestChannel", "contentType",
            "fileSize");
    private static final Set<String> READY_OPTIONAL = Set.of("emailMeta");
    private static final Set<String> FAILED_REQUIRED = Set.of("fileId", "uploadedBy", "ingestChannel",
            "failureReason");
    private static final Set<String> INGEST_CHANNELS = Set.of("DIRECT_UPLOAD", "EMAIL_INTAKE", "SYSTEM_GENERATED");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final EventEnvelopeCodec codec;
    private final ProcessedEventStore processedEvents;
    private final RecordFileScanResultUseCase recordResult;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitMQConfig.FILE_READY_QUEUE)
    public void onReady(Message message) {
        EventEnvelope envelope = parse(message.getBody());
        MilestoneEvidenceCommands.ScanResult command = toReadyCommand(envelope);
        consume(READY_CONSUMER, envelope, command);
    }

    @RabbitListener(queues = RabbitMQConfig.FILE_FAILED_QUEUE)
    public void onFailed(Message message) {
        EventEnvelope envelope = parse(message.getBody());
        MilestoneEvidenceCommands.ScanResult command = toFailedCommand(envelope);
        consume(FAILED_CONSUMER, envelope, command);
    }

    private void consume(String consumer, EventEnvelope envelope, MilestoneEvidenceCommands.ScanResult command) {
        transactionTemplate.executeWithoutResult(status -> {
            if (processedEvents.markProcessed(consumer, envelope)) {
                recordResult.execute(command);
            } else {
                log.info("event=file_scan_event_duplicate eventId={} type={}", envelope.eventId(),
                        envelope.eventType());
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

    MilestoneEvidenceCommands.ScanResult toReadyCommand(EventEnvelope envelope) {
        Map<String, Object> payload = payload(envelope, READY, READY_REQUIRED, READY_OPTIONAL);
        if (!(payload.get("contentType") instanceof String)) {
            throw new InvalidEventPayloadException("contentType is not a string", null);
        }
        if (!(payload.get("fileSize") instanceof Number size) || size.doubleValue() < 0
                || size.doubleValue() != Math.floor(size.doubleValue())) {
            throw new InvalidEventPayloadException("fileSize is not a non-negative integer", null);
        }
        if (payload.containsKey("emailMeta") && !(payload.get("emailMeta") instanceof Map)) {
            throw new InvalidEventPayloadException("emailMeta is not an object", null);
        }
        return new MilestoneEvidenceCommands.ScanResult(envelope.eventId().toString(), fileId(payload),
                EvidenceScanStatus.READY, null);
    }

    MilestoneEvidenceCommands.ScanResult toFailedCommand(EventEnvelope envelope) {
        Map<String, Object> payload = payload(envelope, FAILED, FAILED_REQUIRED, Set.of());
        if (!(payload.get("failureReason") instanceof String reason)) {
            throw new InvalidEventPayloadException("failureReason is not a string", null);
        }
        return new MilestoneEvidenceCommands.ScanResult(envelope.eventId().toString(), fileId(payload),
                EvidenceScanStatus.FAILED, reason);
    }

    private static Map<String, Object> payload(EventEnvelope envelope, String eventType, Set<String> required,
                                               Set<String> optional) {
        if (!eventType.equals(envelope.eventType()) || !PRODUCER.equals(envelope.producer())
                || envelope.eventVersion() != 1) {
            throw new InvalidEventPayloadException("Unexpected event type, producer or version", null);
        }
        Map<String, Object> payload = envelope.payload();
        if (!payload.keySet().containsAll(required) || !payload.keySet().stream()
                .allMatch(key -> required.contains(key) || optional.contains(key))) {
            throw new InvalidEventPayloadException("Payload fields do not match " + eventType, null);
        }
        if (!(payload.get("uploadedBy") instanceof String uploadedBy) || uploadedBy.isEmpty()) {
            throw new InvalidEventPayloadException("uploadedBy is not a non-empty string", null);
        }
        if (!(payload.get("ingestChannel") instanceof String channel) || !INGEST_CHANNELS.contains(channel)) {
            throw new InvalidEventPayloadException("ingestChannel is not a known channel", null);
        }
        return payload;
    }

    private static String fileId(Map<String, Object> payload) {
        if (!(payload.get("fileId") instanceof String value) || !UUID.matcher(value).matches()) {
            throw new InvalidEventPayloadException("fileId is not a UUID", null);
        }
        return value.toLowerCase();
    }
}
