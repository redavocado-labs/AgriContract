package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.dto.InspectionReportCommands;
import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.agricontract.contract.application.usecase.ApplyInspectionReportUseCase;
import com.agricontract.contract.domain.model.inspection.InspectionReport;
import com.agricontract.contract.domain.model.quality.CoffeeActualMetrics;
import com.agricontract.contract.domain.model.quality.InvalidQualityInputException;
import com.agricontract.contract.domain.model.quality.MeasurementStatus;
import com.agricontract.contract.domain.model.terms.CoffeeType;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * inspection.report_confirmed (golden-flow-events.yaml inspectionReportConfirmed, #246) → ApplyInspectionReportUseCase.
 * Parsed here against the schema, and both hashes are recomputed before anything is used (InvalidEventPayloadException:
 * DLQ, no retry); the eventId is claimed and the outcome kept in one transaction. Only LEVEL_1_5 is applied: a LEVEL_2
 * report is acknowledged and ignored with a WARN.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionReportConfirmedConsumer {
    static final String CONSUMER = "contract-service.inspection-report-confirmed";
    static final String EVENT_TYPE = "inspection.report_confirmed";
    static final String PRODUCER = "inspection-service";

    private static final BigDecimal MAX_KG = new BigDecimal("1000000");
    private static final Set<String> REQUIRED = Set.of("reportId", "contractId", "milestoneId", "tier",
            "normalizedResult", "resultHash", "reportFileHash", "reportHash", "confirmedAt");
    private static final Set<String> OPTIONAL = Set.of("inspectorId", "confirmedByOperatorId");
    private static final Set<String> RESULT_REQUIRED = Set.of("resultSchemaVersion", "measuredQuantityKg",
            "acceptedQuantityKg", "measurementStatus", "actualQualityMetrics", "determinedAt");
    private static final Set<String> RESULT_OPTIONAL = Set.of("inconclusiveReasons");
    private static final Set<String> METRICS_KEYS = Set.of("commodity", "type", "moisturePercent",
            "foreignMatterPercent", "blackBrokenBeansPercent");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    private final EventEnvelopeCodec codec;
    private final ProcessedEventStore processedEvents;
    private final ApplyInspectionReportUseCase applyReport;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitMQConfig.INSPECTION_REPORT_CONFIRMED_QUEUE)
    public void onReportConfirmed(Message message) {
        EventEnvelope envelope = parse(message.getBody());
        InspectionReportCommands.ApplyConfirmed command = toCommand(envelope);
        if (command == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (processedEvents.markProcessed(CONSUMER, envelope)) {
                applyReport.execute(command);
            } else {
                log.info("event=inspection_report_event_duplicate eventId={}", envelope.eventId());
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

    /** Null for a report that is acknowledged without being applied (LEVEL_2). */
    InspectionReportCommands.ApplyConfirmed toCommand(EventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType()) || !PRODUCER.equals(envelope.producer())
                || envelope.eventVersion() != 1) {
            throw new InvalidEventPayloadException("Unexpected event type, producer or version", null);
        }
        Map<String, Object> payload = envelope.payload();
        requireKeys(payload, REQUIRED, OPTIONAL, "payload");
        String reportId = uuid(payload, "reportId");
        String contractId = uuid(payload, "contractId");
        String milestoneId = uuid(payload, "milestoneId");
        String tier = text(payload, "tier");
        if (!tier.equals("LEVEL_1_5") && !tier.equals("LEVEL_2")) {
            throw new InvalidEventPayloadException("tier is not a known tier", null);
        }
        if (tier.equals("LEVEL_2")) {
            log.warn("event=inspection_report_ignored reason=tier_not_handled tier={} reportId={} contractId={}"
                    + " milestoneId={} eventId={}", tier, reportId, contractId, milestoneId, envelope.eventId());
            return null;
        }
        String resultHash = hash(payload, "resultHash");
        String reportFileHash = hash(payload, "reportFileHash");
        String reportHash = hash(payload, "reportHash");
        String confirmedAtText = text(payload, "confirmedAt");
        Instant confirmedAt = instant(confirmedAtText, "confirmedAt");
        if (!payload.containsKey("inspectorId")) {
            throw new InvalidEventPayloadException("inspectorId is required for LEVEL_1_5", null);
        }
        String inspectorId = uuid(payload, "inspectorId");
        Map<String, Object> result = object(payload, "normalizedResult");

        try {
            InspectionReportHashes.verify(result, reportId, contractId, milestoneId, tier, reportFileHash, resultHash,
                    reportHash, confirmedAtText, inspectorId);
        } catch (InvalidEventPayloadException e) {
            log.warn("event=inspection_report_hash_mismatch reportId={} contractId={} milestoneId={} eventId={}"
                    + " reason={}", reportId, contractId, milestoneId, envelope.eventId(), e.getMessage());
            throw e;
        }

        requireKeys(result, RESULT_REQUIRED, RESULT_OPTIONAL, "normalizedResult");
        if (!"1.0".equals(result.get("resultSchemaVersion"))) {
            throw new InvalidEventPayloadException("resultSchemaVersion is not 1.0", null);
        }
        MeasurementStatus status = measurementStatus(result);
        if (status == MeasurementStatus.INCONCLUSIVE && !(result.get("inconclusiveReasons") instanceof List<?> reasons
                && !reasons.isEmpty())) {
            throw new InvalidEventPayloadException("inconclusiveReasons is required when INCONCLUSIVE", null);
        }
        instant(text(result, "determinedAt"), "determinedAt");
        BigDecimal measured = kg(result, "measuredQuantityKg");
        BigDecimal accepted = kg(result, "acceptedQuantityKg");
        if (accepted.compareTo(measured) > 0) {
            throw new InvalidEventPayloadException("acceptedQuantityKg exceeds measuredQuantityKg", null);
        }
        CoffeeActualMetrics metrics = metrics(object(result, "actualQualityMetrics"), status);
        InspectionReport report = new InspectionReport(reportId.toLowerCase(), contractId.toLowerCase(),
                milestoneId.toLowerCase(), tier, status, measured, accepted, metrics, resultHash, reportHash,
                confirmedAt, envelope.eventId().toString());
        return new InspectionReportCommands.ApplyConfirmed(report);
    }

    private static MeasurementStatus measurementStatus(Map<String, Object> result) {
        String value = text(result, "measurementStatus");
        try {
            return MeasurementStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventPayloadException("measurementStatus is not known", e);
        }
    }

    /** Required to be well formed when COMPLETE; an INCONCLUSIVE report keeps its metrics only if they are usable. */
    private static CoffeeActualMetrics metrics(Map<String, Object> m, MeasurementStatus status) {
        requireKeys(m, METRICS_KEYS, Set.of(), "actualQualityMetrics");
        try {
            if (!"COFFEE".equals(m.get("commodity"))) {
                throw new InvalidEventPayloadException("commodity is not COFFEE", null);
            }
            return new CoffeeActualMetrics(CoffeeType.valueOf(text(m, "type")), decimal(m, "moisturePercent"),
                    decimal(m, "foreignMatterPercent"), decimal(m, "blackBrokenBeansPercent"));
        } catch (InvalidQualityInputException | IllegalArgumentException | InvalidEventPayloadException e) {
            if (status == MeasurementStatus.INCONCLUSIVE) {
                return null;
            }
            throw e instanceof InvalidEventPayloadException invalid ? invalid
                    : new InvalidEventPayloadException("actualQualityMetrics are not valid", e);
        }
    }

    private static BigDecimal kg(Map<String, Object> result, String key) {
        BigDecimal value = decimal(result, key);
        if (value.signum() < 0 || value.compareTo(MAX_KG) > 0 || value.stripTrailingZeros().scale() > 3) {
            throw new InvalidEventPayloadException(key + " is out of bounds", null);
        }
        return value.setScale(3);
    }

    private static BigDecimal decimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        throw new InvalidEventPayloadException(key + " is not a number", null);
    }

    private static Map<String, Object> object(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Map<?, ?> raw)) {
            throw new InvalidEventPayloadException(key + " is not an object", null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) raw;
        return typed;
    }

    private static void requireKeys(Map<String, Object> map, Set<String> required, Set<String> optional,
                                    String where) {
        if (!map.keySet().containsAll(required)
                || !map.keySet().stream().allMatch(key -> required.contains(key) || optional.contains(key))) {
            throw new InvalidEventPayloadException(where + " fields do not match " + EVENT_TYPE, null);
        }
    }

    private static String text(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String value) || value.isEmpty()) {
            throw new InvalidEventPayloadException(key + " is not a non-empty string", null);
        }
        return value;
    }

    private static String uuid(Map<String, Object> map, String key) {
        String value = text(map, key);
        if (!UUID.matcher(value).matches()) {
            throw new InvalidEventPayloadException(key + " is not a UUID", null);
        }
        return value;
    }

    private static String hash(Map<String, Object> map, String key) {
        String value = text(map, key);
        if (!HASH.matcher(value).matches()) {
            throw new InvalidEventPayloadException(key + " is not a lower-case SHA-256 hex", null);
        }
        return value;
    }

    private static Instant instant(String value, String key) {
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new InvalidEventPayloadException(key + " is not a date-time", e);
        }
    }
}
