package com.agricontract.inspection.application.port;

import java.time.Instant;

public interface ReportFileBindings {
    class BindingConflict extends RuntimeException { public BindingConflict() { super("File binding conflict"); } }
    void bind(String fileId, String commissionId, Instant uploadedAt);
    boolean belongsTo(String fileId, String commissionId);
}
