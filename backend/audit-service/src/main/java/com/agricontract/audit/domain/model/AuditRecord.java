package com.agricontract.audit.domain.model;

import com.agricontract.audit.domain.exception.InvalidAuditRecordException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One immutable audit fact. The typed identity (subject, source, hash, timing) lives in its own fields, not only
 * inside {@code content}. Append-only: there is no way to change a record once built.
 */
public record AuditRecord(
        String recordId,
        SubjectType subjectType,
        String subjectId,
        String sourceType,
        String sourceEventType,
        String sourceEventId,
        String causationId,
        String correlationId,
        String sourceHash,
        Instant occurredAt,
        Map<String, Object> content,
        Instant createdAt) {

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    public AuditRecord {
        recordId = uuid(recordId, "recordId");
        sourceEventId = uuid(sourceEventId, "sourceEventId");
        if (subjectType == null) {
            throw new InvalidAuditRecordException("subjectType is required");
        }
        subjectId = text(subjectId, "subjectId", 100);
        sourceType = text(sourceType, "sourceType", 50);
        sourceEventType = text(sourceEventType, "sourceEventType", 100);
        causationId = optionalText(causationId, "causationId", 64);
        correlationId = optionalText(correlationId, "correlationId", 64);
        if (sourceHash != null && !HASH.matcher(sourceHash).matches()) {
            throw new InvalidAuditRecordException("sourceHash must be 64 lowercase hex characters");
        }
        if (occurredAt == null) {
            throw new InvalidAuditRecordException("occurredAt is required");
        }
        if (createdAt == null) {
            throw new InvalidAuditRecordException("createdAt is required");
        }
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MILLIS); // what is hashed is what TIMESTAMP(6) hands back
        createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
        if (content == null || content.isEmpty()) {
            throw new InvalidAuditRecordException("content is required");
        }
        content = CanonicalJson.normalizeObject(content);
    }

    private static String uuid(String value, String field) {
        if (value == null || !UUID.matcher(value).matches()) {
            throw new InvalidAuditRecordException(field + " must be a UUID");
        }
        return value.toLowerCase();
    }

    private static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidAuditRecordException(field + " is required");
        }
        if (value.length() > maxLength) {
            throw new InvalidAuditRecordException(field + " is longer than " + maxLength);
        }
        return value;
    }

    private static String optionalText(String value, String field, int maxLength) {
        return value == null ? null : text(value, field, maxLength);
    }
}
