package com.agricontract.audit.infrastructure.web;

import com.agricontract.audit.application.dto.AuditRecordPage;
import com.agricontract.audit.application.dto.AuditTrailView;
import com.agricontract.audit.application.dto.ChainVerificationResult;
import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.model.StoredAuditRecord;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** golden-flow-api.yaml schemas of the evidence log (D97). */
final class AuditResponses {

    private AuditResponses() {
    }

    record PartyAuditRecord(String recordId, String sourceType, String sourceEventType, Map<String, Object> content,
                            String recordHash, String createdAt) {
    }

    record ContractAuditTrail(String contractId, String currentRecordHash, String anchorStatus,
                              List<PartyAuditRecord> records) {
        static ContractAuditTrail of(AuditTrailView view) {
            return new ContractAuditTrail(view.contractId(), view.currentRecordHash(), anchor(view.anchored()),
                    view.records().stream().filter(StoredAuditRecord::readable).map(r -> {
                        AuditRecord a = r.record();
                        return new PartyAuditRecord(r.recordId(), a.sourceType(), a.sourceEventType(), a.content(),
                                r.recordHash(), a.createdAt().toString());
                    }).toList());
        }
    }

    record AdminAuditRecord(String recordId, long globalSeq, String subjectType, String subjectId, String sourceType,
                            String sourceEventType, String sourceEventId, Map<String, Object> content,
                            String recordHash, String prevHashSubject, String prevHashGlobal, String createdAt) {
        /** An unreadable (tampered) row still shows its chain columns; its record fields are null. */
        static AdminAuditRecord of(StoredAuditRecord r) {
            AuditRecord a = r.readable() ? r.record() : null;
            return new AdminAuditRecord(r.recordId(), r.globalSeq(), a == null ? null : a.subjectType().name(),
                    a == null ? null : a.subjectId(), a == null ? null : a.sourceType(),
                    a == null ? null : a.sourceEventType(), a == null ? null : a.sourceEventId(),
                    a == null ? null : a.content(), r.recordHash(), r.prevHashSubject(), r.prevHashGlobal(),
                    a == null ? null : a.createdAt().toString());
        }
    }

    record AdminContractAuditTrail(String contractId, String currentRecordHash, String anchorStatus,
                                   List<AdminAuditRecord> records) {
        static AdminContractAuditTrail of(AuditTrailView view) {
            return new AdminContractAuditTrail(view.contractId(), view.currentRecordHash(), anchor(view.anchored()),
                    view.records().stream().map(AdminAuditRecord::of).toList());
        }
    }

    record AdminAuditRecordPage(List<AdminAuditRecord> content, int page, int size, long totalElements,
                                long totalPages) {
        static AdminAuditRecordPage of(AuditRecordPage page) {
            return new AdminAuditRecordPage(page.records().stream().map(AdminAuditRecord::of).toList(), page.page(),
                    page.size(), page.totalElements(), (page.totalElements() + page.size() - 1) / page.size());
        }
    }

    record AuditChainVerification(String scope, String contractId, String status, long checkedRecords,
                                  String brokenRecordId, String brokenReason, String checkedAt, String anchorStatus) {
        static AuditChainVerification of(String contractId, ChainVerificationResult result, Clock clock) {
            ChainVerificationResult.Violation first = result.valid() ? null : result.violations().stream()
                    .min((x, y) -> Long.compare(x.globalSeq(), y.globalSeq())).orElseThrow();
            return new AuditChainVerification(contractId == null ? "GLOBAL" : "CONTRACT", contractId,
                    result.valid() ? "INTACT" : "BROKEN", result.checkedCount(),
                    first == null ? null : first.recordId(), first == null ? null : reason(first.reason()),
                    clock.instant().toString(), anchor(false));
        }

        private static String reason(ChainVerificationResult.Reason reason) {
            return switch (reason) {
                case HASH_MISMATCH, RECORD_UNREADABLE -> "RECORD_HASH_MISMATCH";
                case PREV_SUBJECT_MISMATCH -> "SUBJECT_LINK_BROKEN";
                case PREV_GLOBAL_MISMATCH -> "GLOBAL_LINK_BROKEN";
            };
        }
    }

    private static String anchor(boolean anchored) {
        return anchored ? "ANCHORED" : "NOT_ANCHORED";
    }
}
