package com.agricontract.inspection.application.usecase;

import com.agricontract.inspection.application.port.TransactionRunner;
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

/** Internal confirmation core. No HTTP entry point until trusted session and D62 file adapters exist. */
public final class ConfirmLevel15Report {
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

    public String confirm(String contractId, String milestoneId, String fileId, String content,
                          InspectionSettlementResultV1 result) {
        Objects.requireNonNull(result, "result");
        if (fileId == null || fileId.isBlank()) throw new IllegalArgumentException("File required");
        Instant authTime = session.authenticatedAt();
        requireFresh(authTime, clock.instant());
        ReportIdentity identity = persistence.allocateIdentity(contractId, milestoneId);
        // Do not hold database locks over the remote file operation.
        byte[] bytes = Objects.requireNonNull(files.readReady(identity.assignment().commissionId(), fileId), "verified file");
        String fileHash = sha256(bytes);
        String resultHash = hashes.calculateResultHash(result);
        String normalized = new String(canonicalizer.canonicalize(result), StandardCharsets.UTF_8);
        transactions.inReadCommittedTransaction(() -> {
            assignments.lock(contractId, milestoneId);
            Instant confirmedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            requireFresh(authTime, confirmedAt);
            String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC).format(confirmedAt);
            var commitment = new ReportCommitment(identity.reportId(), contractId, milestoneId,
                    InspectionTier.LEVEL_1_5, fileHash, resultHash, timestamp, identity.assignment().inspectorId());
            var evidence = new ReportEvidence(content, normalized, fileHash, resultHash,
                    hashes.calculateReportHash(commitment), confirmedAt);
            persistence.persist(identity, evidence);
            writes.insertSignature(identity, authTime, confirmedAt, evidence.reportHash());
            lifecycle.closeConfirmed(contractId, milestoneId, identity.assignment().commissionId(),
                    identity.assignment().inspectorId(), confirmedAt);
            writes.appendConfirmed(identity, result, evidence);
        });
        return identity.reportId();
    }

    private void requireFresh(Instant authTime, Instant now) {
        if (authTime == null || authTime.isAfter(now) || Duration.between(authTime, now).compareTo(maxAge) > 0)
            throw new IllegalStateException("Fresh authentication required");
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
