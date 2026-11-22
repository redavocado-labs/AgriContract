package com.agricontract.audit.application.usecase;

import com.agricontract.audit.application.dto.ChainVerificationResult;
import com.agricontract.audit.application.dto.ChainVerificationResult.Reason;
import com.agricontract.audit.application.dto.ChainVerificationResult.Violation;
import com.agricontract.audit.domain.model.ChainedAuditRecord;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.audit.domain.repository.AuditRecordRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * Walks a chain in global_seq order, a page at a time, and reports every record whose hash does not recompute from
 * its stored fields or whose prev pointer is not the previous record's hash. A deleted record shows up as a prev
 * mismatch on the record after it. Wired as a bean in infrastructure.config (no Spring annotation here).
 */
@Slf4j
public class VerifyAuditChainUseCaseImpl implements VerifyAuditChainUseCase {

    static final int PAGE_SIZE = 500;

    private final AuditRecordRepository records;

    public VerifyAuditChainUseCaseImpl(AuditRecordRepository records) {
        this.records = records;
    }

    @Override
    public ChainVerificationResult verifyGlobal() {
        return walk("global", Reason.PREV_GLOBAL_MISMATCH, after -> records.findAfter(after, PAGE_SIZE),
                ChainedAuditRecord::prevHashGlobal);
    }

    @Override
    public ChainVerificationResult verifySubject(SubjectType subjectType, String subjectId) {
        return walk("subject:" + subjectType + ":" + subjectId, Reason.PREV_SUBJECT_MISMATCH,
                after -> records.findAfter(subjectType, subjectId, after, PAGE_SIZE),
                ChainedAuditRecord::prevHashSubject);
    }

    private ChainVerificationResult walk(String scope, Reason prevReason,
                                         LongFunction<List<ChainedAuditRecord>> page,
                                         Function<ChainedAuditRecord, String> prevPointer) {
        List<Violation> violations = new ArrayList<>();
        long checked = 0;
        long afterSeq = 0;
        String previousHash = null;
        List<ChainedAuditRecord> batch;
        while (!(batch = page.apply(afterSeq)).isEmpty()) {
            for (ChainedAuditRecord current : batch) {
                long seq = current.globalSeq();
                if (!current.hashMatches()) {
                    violations.add(new Violation(current.record().recordId(), seq, Reason.HASH_MISMATCH));
                }
                if (!Objects.equals(prevPointer.apply(current), previousHash)) {
                    violations.add(new Violation(current.record().recordId(), seq, prevReason));
                }
                previousHash = current.recordHash();
                afterSeq = seq;
                checked++;
            }
        }
        ChainVerificationResult result = new ChainVerificationResult(violations.isEmpty(), checked, violations);
        if (result.valid()) {
            log.info("event=audit_chain_verified scope={} valid=true checked={}", scope, checked);
        } else {
            log.warn("event=audit_chain_verified scope={} valid=false checked={} violations={}", scope, checked,
                    violations.size());
        }
        return result;
    }
}
