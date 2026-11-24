package com.agricontract.audit.application.dto;

import com.agricontract.audit.domain.model.StoredAuditRecord;

import java.util.List;

/**
 * One contract's chain, oldest first. currentRecordHash is the newest record's hash; anchored is false until an
 * OpenTimestamps anchor covers it (#256 not built: always false).
 */
public record AuditTrailView(String contractId, String currentRecordHash, boolean anchored,
                             List<StoredAuditRecord> records) {
    public AuditTrailView {
        records = List.copyOf(records);
    }
}
