package com.agricontract.inspection.infrastructure.web;

import com.agricontract.inspection.domain.model.CoffeeActualMetrics;
import com.agricontract.inspection.domain.model.CoffeeType;
import com.agricontract.inspection.domain.model.InspectionSettlementResultV1;
import com.agricontract.inspection.domain.model.MeasurementStatus;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Strict frozen submission parsing; no Jackson numeric/string coercion or unknown fields. */
public final class InspectionReportSubmissionParser {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public record Submission(String reportFileId, InspectionSettlementResultV1 normalizedResult) {}

    public Submission parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            fields(root, Set.of("reportFileId", "normalizedResult"));
            String file = text(root, "reportFileId");
            require(UUID.fromString(file).toString().equalsIgnoreCase(file));
            JsonNode result = root.get("normalizedResult");
            fields(result, Set.of("resultSchemaVersion", "measuredQuantityKg", "acceptedQuantityKg",
                    "measurementStatus", "actualQualityMetrics", "inconclusiveReasons", "determinedAt"));
            JsonNode metrics = result.get("actualQualityMetrics");
            fields(metrics, Set.of("commodity", "type", "moisturePercent", "foreignMatterPercent", "blackBrokenBeansPercent"));
            List<String> reasons = null;
            if (result.has("inconclusiveReasons")) {
                JsonNode values = result.get("inconclusiveReasons");
                require(values.isArray());
                reasons = new ArrayList<>();
                for (JsonNode value : values) { require(value.isTextual()); reasons.add(value.textValue()); }
            }
            return new Submission(file, new InspectionSettlementResultV1(text(result, "resultSchemaVersion"),
                    number(result, "measuredQuantityKg"), number(result, "acceptedQuantityKg"),
                    MeasurementStatus.valueOf(text(result, "measurementStatus")),
                    new CoffeeActualMetrics(text(metrics, "commodity"), CoffeeType.valueOf(text(metrics, "type")),
                            number(metrics, "moisturePercent"), number(metrics, "foreignMatterPercent"),
                            number(metrics, "blackBrokenBeansPercent")), reasons, text(result, "determinedAt")));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid inspection report submission", invalid);
        }
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject());
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key)));
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key); require(value != null && value.isTextual()); return value.textValue();
    }
    private static BigDecimal number(JsonNode node, String key) {
        JsonNode value = node.get(key); require(value != null && value.isNumber()); return value.decimalValue();
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid field"); }
}
