package com.agricontract.audit.domain.model;

/**
 * A row as stored. The chain columns are always there; {@code chained} is null when the row cannot be turned back
 * into an audit record (tampered content, unknown enum, unparseable JSON). Reading never throws on such a row, so
 * verification can report it instead of crashing.
 */
public record StoredAuditRecord(
        String recordId,
        long globalSeq,
        String recordHash,
        String prevHashGlobal,
        String prevHashSubject,
        ChainedAuditRecord chained) {

    public boolean readable() {
        return chained != null;
    }

    /** The audit record; only call when {@link #readable()}. */
    public AuditRecord record() {
        if (chained == null) {
            throw new IllegalStateException("stored audit record " + recordId + " is unreadable");
        }
        return chained.record();
    }
}
