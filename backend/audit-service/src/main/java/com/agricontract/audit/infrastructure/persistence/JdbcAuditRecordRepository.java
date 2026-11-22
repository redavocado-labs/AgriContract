package com.agricontract.audit.infrastructure.persistence;

import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.model.ChainedAuditRecord;
import com.agricontract.audit.domain.model.StoredAuditRecord;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Plain JDBC on purpose: the runtime account only has SELECT, INSERT, so there is no ORM that could try an UPDATE.
@Repository
public class JdbcAuditRecordRepository implements AuditRecordRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> CONTENT = new TypeReference<>() { };
    private static final String SOURCE_EVENT_KEY = "uk_audit_record_source_event";

    private static final String LOCK = "SELECT lock_id FROM audit_chain_lock WHERE lock_id = 1 FOR UPDATE";
    private static final String LATEST = "SELECT record_hash FROM audit_record ORDER BY global_seq DESC LIMIT 1";
    private static final String LATEST_SUBJECT = """
            SELECT record_hash FROM audit_record WHERE subject_type = ? AND subject_id = ?
            ORDER BY global_seq DESC LIMIT 1
            """;
    private static final String INSERT = """
            INSERT INTO audit_record (record_id, subject_type, subject_id, source_type, source_event_type,
                                      source_event_id, causation_id, correlation_id, source_hash, occurred_at,
                                      content, created_at, prev_hash_global, prev_hash_subject, record_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String COLUMNS = """
            record_id, subject_type, subject_id, source_type, source_event_type, source_event_id,
            causation_id, correlation_id, source_hash, occurred_at, content, created_at,
            prev_hash_global, prev_hash_subject, record_hash, global_seq
            """;
    private static final String SELECT_BY_SOURCE = "SELECT " + COLUMNS + " FROM audit_record WHERE source_event_id = ?";
    private static final String PAGE = "SELECT " + COLUMNS
            + " FROM audit_record WHERE global_seq > ? ORDER BY global_seq LIMIT ?";
    private static final String SUBJECT_PAGE = "SELECT " + COLUMNS
            + " FROM audit_record WHERE subject_type = ? AND subject_id = ? AND global_seq > ?"
            + " ORDER BY global_seq LIMIT ?";

    private static final RowMapper<StoredAuditRecord> ROW = (rs, rowNum) -> {
        String recordHash = rs.getString("record_hash");
        String prevGlobal = rs.getString("prev_hash_global");
        String prevSubject = rs.getString("prev_hash_subject");
        ChainedAuditRecord chained;
        try {
            chained = new ChainedAuditRecord(
                    new AuditRecord(
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
                            rs.getTimestamp("created_at").toInstant()),
                    prevGlobal, prevSubject, recordHash, rs.getLong("global_seq"));
        } catch (RuntimeException e) {
            // Tampered or corrupt row: never throw here, verification has to be able to report it.
            chained = null;
        }
        return new StoredAuditRecord(rs.getString("record_id"), rs.getLong("global_seq"), recordHash, prevGlobal,
                prevSubject, chained);
    };

    private final JdbcTemplate jdbc;

    public JdbcAuditRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockChain() {
        jdbc.queryForList(LOCK);
    }

    @Override
    public Optional<String> latestHash() {
        return jdbc.queryForList(LATEST, String.class).stream().findFirst();
    }

    @Override
    public Optional<String> latestHash(SubjectType subjectType, String subjectId) {
        return jdbc.queryForList(LATEST_SUBJECT, String.class, subjectType.name(), subjectId).stream().findFirst();
    }

    @Override
    public boolean insert(ChainedAuditRecord chained) {
        AuditRecord record = chained.record();
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
                    Timestamp.from(record.createdAt()),
                    chained.prevHashGlobal(),
                    chained.prevHashSubject(),
                    chained.recordHash());
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
    public Optional<StoredAuditRecord> findBySourceEventId(String sourceEventId) {
        return jdbc.query(SELECT_BY_SOURCE, ROW, sourceEventId).stream().findFirst();
    }

    @Override
    public List<StoredAuditRecord> findAfter(long afterSeq, int limit) {
        return jdbc.query(PAGE, ROW, afterSeq, limit);
    }

    @Override
    public List<StoredAuditRecord> findAfter(SubjectType subjectType, String subjectId, long afterSeq, int limit) {
        return jdbc.query(SUBJECT_PAGE, ROW, subjectType.name(), subjectId, afterSeq, limit);
    }

    private static String writeContent(Map<String, Object> content) {
        try {
            return JSON.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit content is not serializable", e);
        }
    }

    private static Map<String, Object> readContent(String json) {
        try {
            return JSON.readValue(json, CONTENT);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored audit content is not readable", e);
        }
    }
}
