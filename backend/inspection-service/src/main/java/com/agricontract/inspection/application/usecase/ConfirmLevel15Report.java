package com.agricontract.inspection.application.usecase;

import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.application.port.ReportSubmissionIdempotency;
import com.agricontract.inspection.domain.model.*;
import com.agricontract.inspection.domain.repository.AssignmentLifecycleRepository;
import com.agricontract.inspection.domain.service.InspectionCanonicalizer;
import com.agricontract.inspection.domain.service.InspectionHashes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/** Confirms an authorized report and its receipt atomically after verified file retrieval. */
public final class ConfirmLevel15Report {
    public static class FreshAuthenticationRequired extends IllegalStateException {
        public FreshAuthenticationRequired() { super("Fresh authentication required"); }
    }
    public static class IdempotencyConflict extends IllegalStateException {
        public IdempotencyConflict() { super("Idempotency key was used for a different request"); }
    }
    public static class AlreadySigned extends IllegalStateException { }
    /** Implementations must obtain auth time from the verified session, never the request body. */
    public interface Session { Instant authenticatedAt(); }
    /** The implementation must enforce the file-to-commission binding and retrieve READY bytes. */
    public interface VerifiedFiles { byte[] readReady(String commissionId, String fileId); }
    /** Both operations must join the caller's transaction. */
    public interface ConfirmationWrites {
        void insertSignature(ReportIdentity identity, Instant authTime, Instant signedAt, String reportHash);
        void appendConfirmed(ReportIdentity identity, InspectionSettlementResultV1 result, ReportEvidence evidence);
    }

    private final Level15ReportPersistence persistence;
    private final AssignmentLifecycleRepository assignments;
    private final ManageLevel15Assignments lifecycle;
    private final TransactionRunner transactions;
    private final InspectionCanonicalizer canonicalizer;
    private final InspectionHashes hashes;
    private final Session session;
    private final VerifiedFiles files;
    private final ConfirmationWrites writes;
    private final Clock clock;
    private final Duration maxAge;

    public ConfirmLevel15Report(Level15ReportPersistence persistence, AssignmentLifecycleRepository assignments,
            ManageLevel15Assignments lifecycle, TransactionRunner transactions, InspectionCanonicalizer canonicalizer,
            Session session, VerifiedFiles files, ConfirmationWrites writes, Clock clock, Duration maxAge) {
        this.persistence = Objects.requireNonNull(persistence);
        this.assignments = Objects.requireNonNull(assignments);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.transactions = Objects.requireNonNull(transactions);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.hashes = new InspectionHashes(canonicalizer);
        this.session = Objects.requireNonNull(session);
        this.files = Objects.requireNonNull(files);
        this.writes = Objects.requireNonNull(writes);
        this.clock = Objects.requireNonNull(clock);
        this.maxAge = Objects.requireNonNull(maxAge);
        if (maxAge.isNegative() || maxAge.isZero()) throw new IllegalArgumentException("Positive session age required");
    }

    public String confirmIdempotent(String contractId, String milestoneId, String fileId, String content,
            InspectionSettlementResultV1 result, String key, InspectorEligibilityGate eligibility,
            ReportSubmissionIdempotency receipts) {
        if (key == null || !key.matches("[\\x20-\\x7e]{1,255}")) throw new IllegalArgumentException("Invalid idempotency key");
        Objects.requireNonNull(eligibility); Objects.requireNonNull(receipts);
        return confirmInternal(contractId, milestoneId, fileId, content, result, key, eligibility, receipts);
    }

    private String confirmInternal(String contractId, String milestoneId, String fileId, String content,
            InspectionSettlementResultV1 result, String key, InspectorEligibilityGate eligibility,
            ReportSubmissionIdempotency receipts) {
        Objects.requireNonNull(result, "result");
        if (fileId == null || fileId.isBlank()) throw new IllegalArgumentException("File required");
        Instant authTime = session.authenticatedAt();
        requireFresh(authTime, clock.instant());
        String resultHash = hashes.calculateResultHash(result);
        String actor = eligibility.requireEligibleInspector();
        String requestHash = sha256((contractId + "|" + milestoneId + "|" + fileId + "|" + resultHash)
                .getBytes(StandardCharsets.UTF_8));
        var previous = receipts.find(actor, key);
        if (previous.isPresent()) return replay(previous.get(), requestHash);
        ReportIdentity identity;
        try { identity = persistence.allocateIdentity(contractId, milestoneId); }
        catch (com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException denied) {
            var racedReceipt = receipts.find(actor, key);
            if (racedReceipt.isPresent()) return replay(racedReceipt.get(), requestHash);
            if (persistence.alreadyConfirmedForInspector(contractId, milestoneId, actor)) throw new AlreadySigned();
            throw denied;
        }
        // Do not hold database locks over the remote file operation.
        byte[] bytes = Objects.requireNonNull(files.readReady(identity.assignment().commissionId(), fileId), "verified file");
        String fileHash = sha256(bytes);
        String normalized = new String(canonicalizer.canonicalize(result), StandardCharsets.UTF_8);
        var outcome = new java.util.concurrent.atomic.AtomicReference<String>(identity.reportId());
        try { transactions.inReadCommittedTransaction(() -> {
            assignments.lock(contractId, milestoneId);
            Instant confirmedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            requireFresh(authTime, confirmedAt);
            var racedReceipt = receipts.find(actor, key);
            if (racedReceipt.isPresent()) { outcome.set(replay(racedReceipt.get(), requestHash)); return; }
            String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC).format(confirmedAt);
            var commitment = new ReportCommitment(identity.reportId(), contractId, milestoneId,
                    InspectionTier.LEVEL_1_5, fileHash, resultHash, timestamp, identity.assignment().inspectorId());
            var evidence = new ReportEvidence(content, normalized, fileHash, resultHash,
                    hashes.calculateReportHash(commitment), confirmedAt, fileId);
            persistence.persist(identity, evidence);
            writes.insertSignature(identity, authTime, confirmedAt, evidence.reportHash());
            lifecycle.closeConfirmed(contractId, milestoneId, identity.assignment().commissionId(),
                    identity.assignment().inspectorId(), confirmedAt);
            writes.appendConfirmed(identity, result, evidence);
            receipts.insert(actor, key, requestHash, identity.reportId());
        }); } catch (ReportSubmissionIdempotency.ConcurrentKey race) {
            var racedReceipt = receipts.find(actor, key).orElseThrow(() -> race);
            return replay(racedReceipt, requestHash);
        } catch (com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException denied) {
            var racedReceipt = receipts.find(actor, key);
            if (racedReceipt.isPresent()) return replay(racedReceipt.get(), requestHash);
            if (persistence.alreadyConfirmedForInspector(contractId, milestoneId, actor)) throw new AlreadySigned();
            throw denied;
        }
        return outcome.get();
    }

    private static String replay(ReportSubmissionIdempotency.Receipt receipt, String hash) {
        if (!receipt.requestHash().equals(hash)) throw new IdempotencyConflict();
        return receipt.reportId();
    }

    private void requireFresh(Instant authTime, Instant now) {
        if (authTime == null || authTime.isAfter(now) || Duration.between(authTime, now).compareTo(maxAge) > 0)
            throw new FreshAuthenticationRequired();
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
