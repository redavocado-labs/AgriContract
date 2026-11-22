package com.agricontract.audit.infrastructure.messaging;

import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.application.usecase.IngestAuditEventUseCase;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.audit.infrastructure.config.RabbitConfiguration;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.InvalidEnvelopeException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads contract.signed exactly as golden-flow-events.yaml freezes it and hands an AuditIngestCommand to the use
 * case. Anything that does not match is an InvalidEventPayloadException (DLQ, no retry). The envelope is validated by
 * the shared codec; the payload is read a second time with BigDecimal numbers, because the codec's Map would turn
 * reservedQuantity into a double and lose its exact text.
 */
@Slf4j
@Component
public class ContractSignedConsumer {

    static final String SOURCE_TYPE = "CONTRACT_SIGNED";
    static final String EVENT_TYPE = "contract.signed";

    /** The frozen payload keys, in the order content is written. */
    static final List<String> PAYLOAD_KEYS = List.of("contractId", "listingId", "reservedQuantity", "commodity",
            "buyerId", "sellerId", "totalAmount", "buyerDepositAmount", "sellerDepositAmount", "signedContentHash",
            "signedAt");
    private static final List<String> UUID_KEYS = List.of("contractId", "listingId", "buyerId", "sellerId");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    private static final ObjectMapper EXACT_NUMBERS = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            // Default strips trailing zeros: 1250.50 would become 1250.5.
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private final EventEnvelopeCodec codec;
    private final IngestAuditEventUseCase ingest;

    public ContractSignedConsumer(EventEnvelopeCodec codec, IngestAuditEventUseCase ingest) {
        this.codec = codec;
        this.ingest = ingest;
    }

    @RabbitListener(queues = RabbitConfiguration.CONTRACT_SIGNED_QUEUE)
    public void onContractSigned(Message message) {
        ingest.execute(toCommand(message.getBody()));
    }

    AuditIngestCommand toCommand(byte[] body) {
        EventEnvelope envelope;
        try {
            envelope = codec.parse(body);
        } catch (InvalidEnvelopeException | IllegalArgumentException e) {
            throw new InvalidEventPayloadException("Malformed event envelope");
        }
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            throw new InvalidEventPayloadException("Unexpected event type");
        }

        JsonNode payload = exactPayload(body);
        Map<String, String> content = new LinkedHashMap<>();
        for (String key : PAYLOAD_KEYS) {
            content.put(key, value(payload, key));
        }
        payload.fieldNames().forEachRemaining(name -> {
            if (!PAYLOAD_KEYS.contains(name)) {
                log.debug("event=audit_payload_key_ignored eventType={} key={}", EVENT_TYPE, name);
            }
        });
        for (String key : UUID_KEYS) {
            if (!UUID.matcher(content.get(key)).matches()) {
                throw new InvalidEventPayloadException(key + " is not a UUID");
            }
        }
        String hash = content.get("signedContentHash");
        if (!HASH.matcher(hash).matches()) {
            throw new InvalidEventPayloadException("signedContentHash is not 64 lowercase hex characters");
        }

        String contractId = content.get("contractId");
        if (envelope.subject() != null && (envelope.subject().type() != com.agricontract.eventing.SubjectType.CONTRACT
                || !contractId.equals(envelope.subject().id()))) {
            throw new InvalidEventPayloadException("Envelope subject does not match payload.contractId");
        }

        return new AuditIngestCommand(SubjectType.CONTRACT, contractId, SOURCE_TYPE, envelope.eventType(),
                envelope.eventId().toString(), envelope.causationId(), envelope.correlationId(), hash,
                envelope.occurredAt(), new LinkedHashMap<String, Object>(content));
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

    /** Text stays text, a number becomes its plain decimal text; anything else (null, object, boolean) is invalid. */
    private static String value(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        if (node == null || node.isNull()) {
            throw new InvalidEventPayloadException(key + " is required");
        }
        if (node.isTextual() && !node.textValue().isEmpty()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            return node.decimalValue().toPlainString();
        }
        throw new InvalidEventPayloadException(key + " has an unsupported type");
    }
}
