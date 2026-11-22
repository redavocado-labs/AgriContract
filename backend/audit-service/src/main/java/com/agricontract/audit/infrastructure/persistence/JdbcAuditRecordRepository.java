package com.agricontract.audit.infrastructure.persistence;

import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.audit.domain.repository.AuditRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;

// Plain JDBC on purpose: the runtime account only has SELECT, INSERT, so there is no ORM that could try an UPDATE.
@Repository
public class JdbcAuditRecordRepository implements AuditRecordRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> CONTENT = new TypeReference<>() { };
    private static final String SOURCE_EVENT_KEY = "uk_audit_record_source_event";

    private static final String INSERT = """
            INSERT INTO audit_record (record_id, subject_type, subject_id, source_type, source_event_type,
                                      source_event_id, causation_id, correlation_id, source_hash, occurred_at,
                                      content, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_BY_SOURCE = """
            SELECT record_id, subject_type, subject_id, source_type, source_event_type, source_event_id,
                   causation_id, correlation_id, source_hash, occurred_at, content, created_at
            FROM audit_record WHERE source_event_id = ?
            """;

    private static final RowMapper<AuditRecord> ROW = (rs, rowNum) -> new AuditRecord(
            rs.getString("record_id"),
            SubjectType.valueOf(rs.getString("subject_type")),
            rs.getString("subject_id"),
            rs.getString("source_type"),
            rs.getString("source_event_type"),
            rs.getString("source_event_id"),
            rs.getString("causation_id"),
            rs.getString("correlation_id"),
            rs.getString("source_hash"),
            rs.getTimestamp("occurred_at").toInstant(),
            readContent(rs.getString("content")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcAuditRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(AuditRecord record) {
        try {
            jdbc.update(INSERT,
                    record.recordId(),
                    record.subjectType().name(),
                    record.subjectId(),
                    record.sourceType(),
                    record.sourceEventType(),
                    record.sourceEventId(),
                    record.causationId(),
                    record.correlationId(),
                    record.sourceHash(),
                    Timestamp.from(record.occurredAt()),
                    writeContent(record.content()),
                    Timestamp.from(record.createdAt()));
            return true;
        } catch (DuplicateKeyException e) {
            // Only a repeated source event is an idempotent replay; any other unique key (record_id) is a real conflict.
            if (e.getMessage() != null && e.getMessage().contains(SOURCE_EVENT_KEY)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<AuditRecord> findBySourceEventId(String sourceEventId) {
        return jdbc.query(SELECT_BY_SOURCE, ROW, sourceEventId).stream().findFirst();
    }

    private static String writeContent(Map<String, String> content) {
        try {
            return JSON.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit content is not serializable", e);
        }
    }

    private static Map<String, String> readContent(String json) {
        try {
            return JSON.readValue(json, CONTENT);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored audit content is not readable", e);
        }
    }
}
