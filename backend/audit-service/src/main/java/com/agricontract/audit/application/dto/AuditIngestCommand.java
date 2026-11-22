package com.agricontract.audit.application.dto;

import com.agricontract.audit.domain.model.SubjectType;

import java.time.Instant;
import java.util.Map;

/** One source event to record. content holds the payload values as received, numbers as plain decimal text. */
public record AuditIngestCommand(
        SubjectType subjectType,
        String subjectId,
        String sourceType,
        String sourceEventType,
        String sourceEventId,
        String causationId,
        String correlationId,
        String sourceHash,
        Instant occurredAt,
        Map<String, Object> content) {
}
