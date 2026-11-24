package com.agricontract.audit.application.dto;

import com.agricontract.audit.domain.model.StoredAuditRecord;

import java.util.List;

/** One page of the global chain, newest first. */
public record AuditRecordPage(List<StoredAuditRecord> records, int page, int size, long totalElements) {
    public AuditRecordPage {
        records = List.copyOf(records);
    }
}
