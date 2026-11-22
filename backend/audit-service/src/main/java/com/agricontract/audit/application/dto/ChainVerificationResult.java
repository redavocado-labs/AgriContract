package com.agricontract.audit.application.dto;

import java.util.List;

/** Outcome of walking a chain: how many records were looked at and every problem found. */
public record ChainVerificationResult(boolean valid, long checkedCount, List<Violation> violations) {

    public enum Reason { HASH_MISMATCH, PREV_GLOBAL_MISMATCH, PREV_SUBJECT_MISMATCH }

    public record Violation(String recordId, long globalSeq, Reason reason) {
    }

    public ChainVerificationResult {
        violations = List.copyOf(violations);
    }
}
