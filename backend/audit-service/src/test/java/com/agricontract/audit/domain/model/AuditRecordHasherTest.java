package com.agricontract.audit.domain.model;

import com.agricontract.audit.domain.exception.InvalidAuditRecordException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRecordHasherTest {

    static final String UUID_1 = "11111111-1111-1111-1111-111111111111";
    static final String UUID_2 = "22222222-2222-2222-2222-222222222222";
    static final String UUID_3 = "33333333-3333-3333-3333-333333333333";
    static final String UUID_4 = "44444444-4444-4444-4444-444444444444";
    static final String HASH_A = "a".repeat(64);
    static final String HASH_B = "b".repeat(64);

    static final String GOLDEN_CANONICAL = "{\"causationId\":null,\"content\":{\"contractId\":"
            + "\"22222222-2222-2222-2222-222222222222\",\"legs\":[{\"amount\":\"10.00\",\"role\":\"SELLER\"}],"
            + "\"totalAmount\":\"1500000.00\"},\"correlationId\":\"corr-1\",\"createdAt\":\"2026-11-20T08:00:01.123Z\","
            + "\"occurredAt\":\"2026-11-20T08:00:00.000Z\",\"prevHashGlobal\":null,\"prevHashSubject\":null,"
            + "\"recordId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"sourceEventId\":\"33333333-3333-3333-3333-333333333333\",\"sourceEventType\":\"contract.signed\","
            + "\"sourceHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
            + "\"sourceType\":\"CONTRACT_SIGNED\",\"subjectId\":\"22222222-2222-2222-2222-222222222222\","
            + "\"subjectType\":\"CONTRACT\"}";
    static final String GOLDEN_HASH = "eb6e96714dc1fe8caeba108299ae9a75ed9c2857f5c55a6849c1d3ae8d9e6610";

    static Map<String, Object> content() {
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("role", "SELLER");
        leg.put("amount", "10.00");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("contractId", UUID_2);
        content.put("totalAmount", "1500000.00");
        content.put("legs", List.of(leg));
        return content;
    }

    static AuditRecord golden() {
        return new AuditRecord(UUID_1, SubjectType.CONTRACT, UUID_2, "CONTRACT_SIGNED", "contract.signed", UUID_3,
                null, "corr-1", HASH_A, Instant.parse("2026-11-20T08:00:00Z"), content(),
                Instant.parse("2026-11-20T08:00:01.123Z"));
    }

    @Test
    void goldenVectorCanonicalJsonAndHash() {
        assertThat(AuditRecordHasher.canonical(golden(), null, null)).isEqualTo(GOLDEN_CANONICAL);
        assertThat(AuditRecordHasher.hash(golden(), null, null)).isEqualTo(GOLDEN_HASH);
    }

    @Test
    void timestampsAreTruncatedToMilliseconds() {
        AuditRecord record = new AuditRecord(UUID_1, SubjectType.CONTRACT, UUID_2, "CONTRACT_SIGNED",
                "contract.signed", UUID_3, null, "corr-1", HASH_A, Instant.parse("2026-11-20T08:00:00.1239999Z"),
                content(), Instant.parse("2026-11-20T08:00:01.123456Z"));
        assertThat(record.createdAt()).isEqualTo(Instant.parse("2026-11-20T08:00:01.123Z"));
        assertThat(record.occurredAt()).isEqualTo(Instant.parse("2026-11-20T08:00:00.123Z"));
    }

    /** One entry per hashed field (14); each variant changes exactly that field. */
    @ParameterizedTest
    @ValueSource(strings = {"recordId", "subjectType", "subjectId", "sourceType", "sourceEventType", "sourceEventId",
            "causationId", "correlationId", "sourceHash", "occurredAt", "content", "prevHashGlobal",
            "prevHashSubject", "createdAt"})
    void hashChangesWhenAnySingleFieldChanges(String field) {
        String base = AuditRecordHasher.hash(golden(), null, null);
        String changed = switch (field) {
            case "prevHashGlobal" -> AuditRecordHasher.hash(golden(), HASH_B, null);
            case "prevHashSubject" -> AuditRecordHasher.hash(golden(), null, HASH_B);
            default -> AuditRecordHasher.hash(variant(field), null, null);
        };
        assertThat(changed).isNotEqualTo(base);
    }

    private static AuditRecord variant(String field) {
        Map<String, Object> other = new LinkedHashMap<>(content());
        other.put("totalAmount", "1500000.01");
        return new AuditRecord(
                field.equals("recordId") ? UUID_4 : UUID_1,
                field.equals("subjectType") ? SubjectType.USER_PAIR : SubjectType.CONTRACT,
                field.equals("subjectId") ? UUID_4 : UUID_2,
                field.equals("sourceType") ? "OTHER" : "CONTRACT_SIGNED",
                field.equals("sourceEventType") ? "contract.other" : "contract.signed",
                field.equals("sourceEventId") ? UUID_4 : UUID_3,
                field.equals("causationId") ? "cause-1" : null,
                field.equals("correlationId") ? "corr-2" : "corr-1",
                field.equals("sourceHash") ? HASH_B : HASH_A,
                Instant.parse(field.equals("occurredAt") ? "2026-11-20T08:00:00.001Z" : "2026-11-20T08:00:00Z"),
                field.equals("content") ? other : content(),
                Instant.parse(field.equals("createdAt") ? "2026-11-20T08:00:01.124Z" : "2026-11-20T08:00:01.123Z"));
    }

    @Test
    void numberLeafIsRejected() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("amount", 10);
        assertThatThrownBy(() -> record(bad)).isInstanceOf(InvalidAuditRecordException.class)
                .hasMessageContaining("content.amount");
    }

    @Test
    void booleanLeafIsRejectedEvenWhenNested() {
        Map<String, Object> bad = Map.of("legs", List.of(Map.of("paid", true)));
        assertThatThrownBy(() -> record(bad)).isInstanceOf(InvalidAuditRecordException.class)
                .hasMessageContaining("content.legs[0].paid");
    }

    @Test
    void contentIsDeeplyImmutable() {
        List<Object> legs = (List<Object>) golden().content().get("legs");
        assertThatThrownBy(() -> legs.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    private static AuditRecord record(Map<String, Object> content) {
        return new AuditRecord(UUID_1, SubjectType.CONTRACT, UUID_2, "T", "e.t", UUID_3, null, null, null,
                Instant.parse("2026-11-20T08:00:00Z"), content, Instant.parse("2026-11-20T08:00:01Z"));
    }
}
