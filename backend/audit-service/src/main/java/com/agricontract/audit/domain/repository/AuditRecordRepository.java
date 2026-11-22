package com.agricontract.audit.domain.repository;

import com.agricontract.audit.domain.model.AuditRecord;

import java.util.Optional;

/** Append-only by construction: insert and reads, no update, no delete. */
public interface AuditRecordRepository {

    /** @return true when the row was written, false when a record with that sourceEventId is already there. */
    boolean insert(AuditRecord record);

    Optional<AuditRecord> findBySourceEventId(String sourceEventId);
}
