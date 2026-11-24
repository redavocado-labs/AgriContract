package com.agricontract.audit.application.usecase;

import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.application.dto.AuditRecordPage;
import com.agricontract.audit.application.dto.AuditTrailView;
import com.agricontract.audit.application.dto.ChainVerificationResult;
import com.agricontract.audit.application.exception.AuditTrailNotFoundException;
import com.agricontract.audit.application.port.TransactionRunner;
import com.agricontract.audit.domain.model.StoredAuditRecord;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.audit.domain.repository.AuditRecordRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The evidence log of hash-chain §4.6 (D97). A party of a contract reads and checks its contract's chain; ADMIN reads
 * and checks everything. Every ADMIN read appends one AUDIT_VIEWED record (subject SYSTEM) in the transaction of the
 * read, so ADMIN cannot read evidence without a tamper-evident trace; a party's read is only logged. Wired as a bean
 * in infrastructure.config (no Spring annotation here).
 */
@Slf4j
public class ReadAuditTrailUseCase {

    static final String VIEWED_SOURCE_TYPE = "AUDIT_VIEWED";
    static final String VIEWED_EVENT_TYPE = "audit.viewed";
    private static final String SIGNED = "CONTRACT_SIGNED";
    private static final int PAGE = 500;

    private final AuditRecordRepository records;
    private final VerifyAuditChainUseCase verify;
    private final IngestAuditEventUseCase ingest;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ReadAuditTrailUseCase(AuditRecordRepository records, VerifyAuditChainUseCase verify,
                                 IngestAuditEventUseCase ingest, TransactionRunner transactions, Clock clock) {
        this.records = records;
        this.verify = verify;
        this.ingest = ingest;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The contract's chain for one of its parties; 404 for anyone else and for a contract with no record. */
    public AuditTrailView forParty(String contractId, String userId) {
        AuditTrailView view = trail(contractId);
        requireParty(view, userId);
        log.info("event=audit_trail_viewed contractId={} userId={} records={}", contractId, userId,
                view.records().size());
        return view;
    }

    public ChainVerificationResult verifyForParty(String contractId, String userId) {
        requireParty(trail(contractId), userId);
        return verify.verifySubject(SubjectType.CONTRACT, contractId);
    }

    /** The contract's chain for ADMIN; the read is recorded (AUDIT_VIEWED) or nothing is returned. */
    public AuditTrailView forAdmin(String contractId, String adminUserId) {
        // The view is written first: the ingest takes the chain lock, which must be the transaction's first statement.
        // No such contract rolls the view back with the 404.
        return transactions.inTransaction(() -> {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("adminUserId", adminUserId);
            content.put("scope", "CONTRACT");
            content.put("contractId", contractId);
            recordView(content);
            return trail(contractId);
        });
    }

    public ChainVerificationResult verifyForAdmin(String contractId) {
        trail(contractId);
        return verify.verifySubject(SubjectType.CONTRACT, contractId);
    }

    /** One page of the global chain, newest first, for ADMIN; the read is recorded first, so it is on page 0. */
    public AuditRecordPage globalForAdmin(SubjectType subjectType, int page, int size, String adminUserId) {
        return transactions.inTransaction(() -> {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("adminUserId", adminUserId);
            content.put("scope", "GLOBAL");
            content.put("subjectTypeFilter", subjectType == null ? null : subjectType.name());
            content.put("page", Integer.toString(page));
            recordView(content);
            return new AuditRecordPage(records.findNewestFirst(subjectType, page * size, size), page, size,
                    records.count(subjectType));
        });
    }

    public ChainVerificationResult verifyGlobal() {
        return verify.verifyGlobal();
    }

    private AuditTrailView trail(String contractId) {
        List<StoredAuditRecord> all = new ArrayList<>();
        long after = 0;
        List<StoredAuditRecord> batch;
        while (!(batch = records.findAfter(SubjectType.CONTRACT, contractId, after, PAGE)).isEmpty()) {
            all.addAll(batch);
            after = batch.getLast().globalSeq();
        }
        if (all.isEmpty()) {
            throw new AuditTrailNotFoundException(contractId);
        }
        return new AuditTrailView(contractId, all.getLast().recordHash(), false, all);
    }

    /** The parties are the buyer and seller of the contract's CONTRACT_SIGNED record (no call to contract-service). */
    private static void requireParty(AuditTrailView view, String userId) {
        boolean party = view.records().stream()
                .filter(StoredAuditRecord::readable)
                .filter(r -> SIGNED.equals(r.record().sourceType()))
                .map(r -> r.record().content())
                .anyMatch(c -> userId.equalsIgnoreCase(String.valueOf(c.get("buyerId")))
                        || userId.equalsIgnoreCase(String.valueOf(c.get("sellerId"))));
        if (!party) {
            throw new AuditTrailNotFoundException(view.contractId());
        }
    }

    private void recordView(Map<String, Object> content) {
        ingest.execute(new AuditIngestCommand(SubjectType.SYSTEM, "SYSTEM", VIEWED_SOURCE_TYPE, VIEWED_EVENT_TYPE,
                UUID.randomUUID().toString(), null, null, null, clock.instant(), content));
    }
}
