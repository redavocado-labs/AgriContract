package com.agricontract.audit.domain.model;

import com.agricontract.audit.domain.exception.InvalidAuditRecordException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRecordTest {

    static final String HASH = "0123456789abcdef".repeat(4);

    static AuditRecord build(String recordId, SubjectType type, String subjectId, String sourceType, String eventType,
                             String eventId, String hash, Instant occurredAt, Map<String, String> content,
                             Instant createdAt) {
        return new AuditRecord(recordId, type, subjectId, sourceType, eventType, eventId, "cause", "corr", hash,
                occurredAt, content, createdAt);
    }

    static AuditRecord valid() {
        return build(UUID.randomUUID().toString(), SubjectType.CONTRACT, "c-1", "CONTRACT_SIGNED", "contract.signed",
                UUID.randomUUID().toString(), HASH, Instant.now(), Map.of("k", "v"), Instant.now());
    }

    @Test
    void acceptsAValidRecordWithNullableFieldsAbsent() {
        AuditRecord record = new AuditRecord(UUID.randomUUID().toString(), SubjectType.SYSTEM, "s", "X", "x.y",
                UUID.randomUUID().toString(), null, null, null, Instant.now(), Map.of("k", "v"), Instant.now());
        assertThat(record.sourceHash()).isNull();
        assertThat(record.causationId()).isNull();
    }

    @Test
    void rejectsEachMissingRequiredField() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, String> c = Map.of("k", "v");
        assertInvalid(() -> build(null, SubjectType.CONTRACT, "s", "T", "e.t", id, HASH, now, c, now));
        assertInvalid(() -> build(id, null, "s", "T", "e.t", id, HASH, now, c, now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, null, "T", "e.t", id, HASH, now, c, now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, " ", "T", "e.t", id, HASH, now, c, now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, "s", null, "e.t", id, HASH, now, c, now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, "s", "T", null, id, HASH, now, c, now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, "s", "T", "e.t", null, HASH, now, c, now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, "s", "T", "e.t", id, HASH, null, c, now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, "s", "T", "e.t", id, HASH, now, null, now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, "s", "T", "e.t", id, HASH, now, Map.of(), now));
        assertInvalid(() -> build(id, SubjectType.CONTRACT, "s", "T", "e.t", id, HASH, now, c, null));
    }

    @Test
    void rejectsABadSourceHash() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        for (String bad : new String[] {"abc", HASH.toUpperCase(), HASH + "0", "g".repeat(64), ""}) {
            assertInvalid(() -> build(id, SubjectType.CONTRACT, "s", "T", "e.t", id, bad, now, Map.of("k", "v"), now));
        }
    }

    @Test
    void rejectsABadSourceEventId() {
        Instant now = Instant.now();
        assertInvalid(() -> build(UUID.randomUUID().toString(), SubjectType.CONTRACT, "s", "T", "e.t", "not-a-uuid",
                HASH, now, Map.of("k", "v"), now));
    }

    @Test
    void rejectsTextLongerThanItsColumn() {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        assertInvalid(() -> build(id, SubjectType.CONTRACT, "s".repeat(101), "T", "e.t", id, HASH, now, Map.of("k", "v"), now));
    }

    @Test
    void contentCannotBeChangedAfterwards() {
        assertThatThrownBy(() -> valid().content().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertInvalid(Runnable build) {
        assertThatThrownBy(build::run).isInstanceOf(InvalidAuditRecordException.class);
    }
}
