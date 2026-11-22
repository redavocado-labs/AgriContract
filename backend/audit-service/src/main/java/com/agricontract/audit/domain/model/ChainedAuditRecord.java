package com.agricontract.audit.domain.model;

import com.agricontract.audit.domain.exception.InvalidAuditRecordException;

import java.util.regex.Pattern;

/**
 * An audit record plus its place in the two chains. {@code globalSeq} is the insert order assigned by the database
 * (null before the insert); it is not part of the hash.
 */
public record ChainedAuditRecord(
        AuditRecord record,
        String prevHashGlobal,
        String prevHashSubject,
        String recordHash,
        Long globalSeq) {

    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    public ChainedAuditRecord {
        if (record == null) {
            throw new InvalidAuditRecordException("record is required");
        }
        hex(prevHashGlobal, true, "prevHashGlobal");
        hex(prevHashSubject, true, "prevHashSubject");
        hex(recordHash, false, "recordHash");
    }

    /** Links a new record to the current tails of both chains and computes its hash. */
    public static ChainedAuditRecord chain(AuditRecord record, String prevHashGlobal, String prevHashSubject) {
        return new ChainedAuditRecord(record, prevHashGlobal, prevHashSubject,
                AuditRecordHasher.hash(record, prevHashGlobal, prevHashSubject), null);
    }

    /** True when the stored hash is what the stored fields hash to. */
    public boolean hashMatches() {
        return recordHash.equals(AuditRecordHasher.hash(record, prevHashGlobal, prevHashSubject));
    }

    private static void hex(String value, boolean nullable, String field) {
        if (value == null ? !nullable : !HASH.matcher(value).matches()) {
            throw new InvalidAuditRecordException(field + " must be 64 lowercase hex characters");
        }
    }
}
