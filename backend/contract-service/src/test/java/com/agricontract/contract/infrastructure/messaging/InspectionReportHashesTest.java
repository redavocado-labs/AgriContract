package com.agricontract.contract.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InspectionReportHashesTest {
    static final String REPORT = "11111111-1111-4111-8111-111111111111";
    static final String CONTRACT = "22222222-2222-4222-8222-222222222222";
    static final String MILESTONE = "33333333-3333-4333-8333-333333333333";
    static final String INSPECTOR = "44444444-4444-4444-8444-444444444444";
    static final String FILE_HASH = "a".repeat(64);
    static final String CONFIRMED_AT = "2026-11-20T03:00:00Z";

    static Map<String, Object> result() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("commodity", "COFFEE");
        metrics.put("type", "ROBUSTA");
        metrics.put("moisturePercent", 13.5);
        metrics.put("foreignMatterPercent", 0.8);
        metrics.put("blackBrokenBeansPercent", 7.5);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultSchemaVersion", "1.0");
        result.put("measuredQuantityKg", 9800.0);
        result.put("acceptedQuantityKg", 9800.0);
        result.put("measurementStatus", "COMPLETE");
        result.put("actualQualityMetrics", metrics);
        result.put("determinedAt", "2026-11-20T02:00:00Z");
        return result;
    }

    @Test
    void goodHashesVerify_andKeyOrderDoesNotMatter() {
        String resultHash = InspectionReportHashes.resultHash(result());
        String reportHash = InspectionReportHashes.reportHash(REPORT, CONTRACT, MILESTONE, "LEVEL_1_5", FILE_HASH,
                resultHash, CONFIRMED_AT, INSPECTOR);
        InspectionReportHashes.verify(result(), REPORT, CONTRACT, MILESTONE, "LEVEL_1_5", FILE_HASH, resultHash,
                reportHash, CONFIRMED_AT, INSPECTOR);

        Map<String, Object> reordered = new LinkedHashMap<>();
        result().entrySet().stream().sorted(Map.Entry.<String, Object>comparingByKey().reversed())
                .forEach(e -> reordered.put(e.getKey(), e.getValue()));
        assertThat(InspectionReportHashes.resultHash(reordered)).isEqualTo(resultHash);
        assertThat(resultHash).matches("[a-f0-9]{64}");
    }

    @Test
    void aTamperedResult_failsOnTheResultHash() {
        String resultHash = InspectionReportHashes.resultHash(result());
        String reportHash = InspectionReportHashes.reportHash(REPORT, CONTRACT, MILESTONE, "LEVEL_1_5", FILE_HASH,
                resultHash, CONFIRMED_AT, INSPECTOR);
        Map<String, Object> tampered = result();
        tampered.put("acceptedQuantityKg", 9900.0);

        assertThatThrownBy(() -> InspectionReportHashes.verify(tampered, REPORT, CONTRACT, MILESTONE, "LEVEL_1_5",
                FILE_HASH, resultHash, reportHash, CONFIRMED_AT, INSPECTOR))
                .isInstanceOf(InvalidEventPayloadException.class).hasMessageContaining("resultHash");
    }

    @Test
    void aTamperedReportHash_orCommitment_failsOnTheReportHash() {
        String resultHash = InspectionReportHashes.resultHash(result());
        String reportHash = InspectionReportHashes.reportHash(REPORT, CONTRACT, MILESTONE, "LEVEL_1_5", FILE_HASH,
                resultHash, CONFIRMED_AT, INSPECTOR);

        assertThatThrownBy(() -> InspectionReportHashes.verify(result(), REPORT, CONTRACT, MILESTONE, "LEVEL_1_5",
                FILE_HASH, resultHash, "b".repeat(64), CONFIRMED_AT, INSPECTOR))
                .isInstanceOf(InvalidEventPayloadException.class).hasMessageContaining("reportHash");
        assertThatThrownBy(() -> InspectionReportHashes.verify(result(), REPORT, CONTRACT, MILESTONE, "LEVEL_1_5",
                FILE_HASH, resultHash, reportHash, "2026-11-20T03:00:01Z", INSPECTOR))
                .isInstanceOf(InvalidEventPayloadException.class).hasMessageContaining("reportHash");
        assertThatThrownBy(() -> InspectionReportHashes.verify(result(), REPORT, CONTRACT, MILESTONE, "LEVEL_1_5",
                "c".repeat(64), resultHash, reportHash, CONFIRMED_AT, INSPECTOR))
                .isInstanceOf(InvalidEventPayloadException.class).hasMessageContaining("reportHash");
    }
}
