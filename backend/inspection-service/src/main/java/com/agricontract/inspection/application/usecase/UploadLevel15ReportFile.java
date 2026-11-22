package com.agricontract.inspection.application.usecase;

import com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException;
import com.agricontract.inspection.application.port.ReportFileBindings;
import com.agricontract.inspection.application.port.ReportFileStore;
import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.domain.repository.AssignmentLifecycleRepository;

import java.io.InputStream;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Stream first, then bind only if the same authorized assignment is still active. */
public final class UploadLevel15ReportFile {
    private final InspectionAuthorization authorization;
    private final InspectorEligibilityGate eligibility;
    private final AssignmentLifecycleRepository assignments;
    private final ReportFileStore files;
    private final ReportFileBindings bindings;
    private final TransactionRunner transactions;
    private final Clock clock;

    public UploadLevel15ReportFile(InspectionAuthorization authorization, InspectorEligibilityGate eligibility,
            AssignmentLifecycleRepository assignments, ReportFileStore files, ReportFileBindings bindings,
            TransactionRunner transactions, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.assignments = Objects.requireNonNull(assignments);
        this.files = Objects.requireNonNull(files);
        this.bindings = Objects.requireNonNull(bindings);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReportFileStore.StoredFile upload(String contract, String milestone, String key,
            String contentType, long length, InputStream body) {
        var assignment = authorization.requireMilestone(contract, milestone);
        var stored = files.storeOnBehalfOf(assignment.inspectorId(), key, contentType, length, body);
        String actor = eligibility.requireEligibleInspector();
        transactions.inReadCommittedTransaction(() -> {
            assignments.lock(contract, milestone);
            var active = assignments.active(contract, milestone).orElseThrow(InspectionAssignmentAccessDeniedException::new);
            if (!active.commissionId().equals(assignment.commissionId()) || !active.inspectorId().equals(actor))
                throw new InspectionAssignmentAccessDeniedException();
            bindings.bind(stored.fileId(), active.commissionId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        });
        return stored;
    }
}
