package com.agricontract.inspection.infrastructure.web;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class InspectionReportSubmissionParserTest {
    final InspectionReportSubmissionParser parser = new InspectionReportSubmissionParser();
    String valid() {
        return """
                {"reportFileId":"%s","normalizedResult":{"resultSchemaVersion":"1.0",
                 "measuredQuantityKg":10.000,"acceptedQuantityKg":9.5,"measurementStatus":"COMPLETE",
                 "actualQualityMetrics":{"commodity":"COFFEE","type":"ROBUSTA","moisturePercent":12.5,
                 "foreignMatterPercent":0,"blackBrokenBeansPercent":0},"determinedAt":"2026-10-25T02:59:00.000Z"}}
                """.formatted(UUID.randomUUID());
    }

    @Test void parsesTypedResult() {
        var result = parser.parse(valid());
        assertThat(result.normalizedResult().acceptedQuantityKg()).isEqualByComparingTo("9.5");
    }

    @Test void rejectsCoercionUnknownFieldsAndInvalidScale() {
        String json = valid();
        assertThatThrownBy(() -> parser.parse(json.replace("10.000", "\"10\""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(json.replace("10.000", "10.0001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(json.replace("\"normalizedResult\":", "\"unknown\":1,\"normalizedResult\":")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(json.replace("\"measurementStatus\":\"COMPLETE\"", "\"measurementStatus\":\"COMPLETE\",\"measurementStatus\":\"COMPLETE\"")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
