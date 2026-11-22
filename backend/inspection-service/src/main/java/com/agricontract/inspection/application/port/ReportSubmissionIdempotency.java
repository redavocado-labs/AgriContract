package com.agricontract.inspection.application.port;

import java.util.Optional;

/** Successful submission receipts are saved in the same transaction as the report. */
public interface ReportSubmissionIdempotency {
    class ConcurrentKey extends RuntimeException { public ConcurrentKey(Throwable cause) { super(cause); } }
    record Receipt(String requestHash, String reportId) {}
    Optional<Receipt> find(String inspectorId, String key);
    void insert(String inspectorId, String key, String requestHash, String reportId);
}
