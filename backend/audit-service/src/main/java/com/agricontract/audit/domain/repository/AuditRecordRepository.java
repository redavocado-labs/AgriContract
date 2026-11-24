package com.agricontract.audit.domain.repository;

import com.agricontract.audit.domain.model.StoredAuditRecord;
import com.agricontract.audit.domain.model.ChainedAuditRecord;
import com.agricontract.audit.domain.model.SubjectType;

import java.util.List;
import java.util.Optional;

/** Append-only by construction: insert and reads, no update, no delete. */
public interface AuditRecordRepository {

    /**
     * Takes the chain mutex until the surrounding transaction ends, so only one chained insert runs at a time.
     * Must be the first statement of the transaction.
     */
    void lockChain();

    /** record_hash of the newest record overall (highest global_seq); empty when there is none. */
    Optional<String> latestHash();

    /** record_hash of the newest record of this subject; empty when the subject has none. */
    Optional<String> latestHash(SubjectType subjectType, String subjectId);

    /** @return true when the row was written, false when a record with that sourceEventId is already there. */
    boolean insert(ChainedAuditRecord record);

    Optional<StoredAuditRecord> findBySourceEventId(String sourceEventId);

    /** Up to {@code limit} records with global_seq greater than {@code afterSeq}, in global_seq order. */
    List<StoredAuditRecord> findAfter(long afterSeq, int limit);

    /** Same as {@link #findAfter} restricted to one subject. */
    List<StoredAuditRecord> findAfter(SubjectType subjectType, String subjectId, long afterSeq, int limit);

    /** D97: one page of the global chain, newest first (global_seq descending), optionally one subject type. */
    List<StoredAuditRecord> findNewestFirst(SubjectType subjectType, int offset, int limit);

    /** D97: how many records {@link #findNewestFirst} pages over. */
    long count(SubjectType subjectType);
}
