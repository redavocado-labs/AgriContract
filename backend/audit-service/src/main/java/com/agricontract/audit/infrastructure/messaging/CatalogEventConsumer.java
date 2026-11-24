package com.agricontract.audit.infrastructure.messaging;

import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.application.usecase.IngestAuditEventUseCase;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.InvalidEnvelopeException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * #255 (hash-chain §2.4, D98): every catalog event except contract.signed, one queue each, read the same way. The
 * envelope is validated by the shared codec and the event type must be one of {@link CatalogEvent#ALL}; the payload is
 * read again with exact numbers. Content is the event's frozen keys minus the excluded contact data, as a tree of text,
 * null, lists and objects (CanonicalJson has no number or boolean leaves: numbers become their plain decimal text,
 * booleans "true"/"false"). A payload outside the frozen shape is an InvalidEventPayloadException: DLQ, no retry.
 */
@Slf4j
@Component
public class CatalogEventConsumer {

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final ObjectMapper EXACT_NUMBERS = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .build();

    private final EventEnvelopeCodec codec;
    private final IngestAuditEventUseCase ingest;

    public CatalogEventConsumer(EventEnvelopeCodec codec, IngestAuditEventUseCase ingest) {
        this.codec = codec;
        this.ingest = ingest;
    }

    @RabbitListener(queues = "#{T(com.agricontract.audit.infrastructure.config.RabbitConfiguration).catalogQueues()}")
    public void onCatalogEvent(Message message) {
        ingest.execute(toCommand(message.getBody()));
    }

    AuditIngestCommand toCommand(byte[] body) {
        EventEnvelope envelope;
        try {
            envelope = codec.parse(body);
        } catch (InvalidEnvelopeException | IllegalArgumentException e) {
            throw new InvalidEventPayloadException("Malformed event envelope");
        }
        CatalogEvent event = CatalogEvent.of(envelope.eventType());
        if (event == null) {
            throw new InvalidEventPayloadException("Event type is not in the audit catalog");
        }
        JsonNode payload = exactPayload(body);

        Map<String, Object> content = new LinkedHashMap<>();
        for (String key : event.required()) {
            JsonNode node = payload.get(key);
            if (node == null || (node.isNull() && !event.nullable().contains(key))) {
                throw new InvalidEventPayloadException(key + " is required");
            }
            keep(event, key, node, content);
        }
        for (String key : event.optional()) {
            JsonNode node = payload.get(key);
            if (node != null && !(node.isNull() && !event.nullable().contains(key))) {
                keep(event, key, node, content);
            } else if (node != null) {
                throw new InvalidEventPayloadException(key + " must not be null");
            }
        }
        payload.fieldNames().forEachRemaining(name -> {
            if (!event.required().contains(name) && !event.optional().contains(name)) {
                log.debug("event=audit_payload_key_ignored eventType={} key={}", event.eventType(), name);
            }
        });
        for (String key : event.uuidKeys()) {
            Object value = content.get(key);
            if (value != null && !(value instanceof String text && UUID.matcher(text).matches())) {
                throw new InvalidEventPayloadException(key + " is not a UUID");
            }
        }
        for (String key : event.hashKeys()) {
            if (!(content.get(key) instanceof String text && HASH.matcher(text).matches())) {
                throw new InvalidEventPayloadException(key + " is not 64 lowercase hex characters");
            }
        }

        String subjectId = event.subjectId(content);
        if (envelope.subject() != null && event.subjectType() == SubjectType.CONTRACT
                && (envelope.subject().type() != com.agricontract.eventing.SubjectType.CONTRACT
                || !subjectId.equals(envelope.subject().id()))) {
            throw new InvalidEventPayloadException("Envelope subject does not match payload.contractId");
        }
        String sourceHash = event.sourceHashKey() == null ? null : (String) content.get(event.sourceHashKey());
        return new AuditIngestCommand(event.subjectType(), subjectId, event.sourceType().apply(payload),
                envelope.eventType(), envelope.eventId().toString(), envelope.causationId(),
                envelope.correlationId(), sourceHash, envelope.occurredAt(), content);
    }

    private static void keep(CatalogEvent event, String key, JsonNode node, Map<String, Object> content) {
        if (!event.excluded().contains(key)) {
            content.put(key, tree(node, key));
        }
    }

    private static JsonNode exactPayload(byte[] body) {
        try {
            JsonNode payload = EXACT_NUMBERS.readTree(body).get("payload");
            if (payload == null || !payload.isObject()) {
                throw new InvalidEventPayloadException("payload is required");
            }
            return payload;
        } catch (IOException e) {
            throw new InvalidEventPayloadException("Malformed event envelope");
        }
    }

    /** JSON to the CanonicalJson tree: text, null, list, object; numbers as plain decimal text, booleans as text. */
    static Object tree(JsonNode node, String path) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            return node.decimalValue().toPlainString();
        }
        if (node.isBoolean()) {
            return Boolean.toString(node.booleanValue());
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                list.add(tree(node.get(i), path + "[" + i + "]"));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fieldNames().forEachRemaining(name -> map.put(name, tree(node.get(name), path + "." + name)));
            return map;
        }
        throw new InvalidEventPayloadException(path + " has an unsupported type");
    }
}
