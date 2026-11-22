package com.agricontract.inspection.application.usecase;

import com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException;
import com.agricontract.inspection.application.port.ReportFileBindings;
import com.agricontract.inspection.application.port.ReportFileStore;
import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.domain.model.InspectionAssignment;
import com.agricontract.inspection.domain.model.TimedAssignment;
import com.agricontract.inspection.domain.repository.AssignmentLifecycleRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadLevel15ReportFileTest {
    final InspectionAuthorization auth = mock(InspectionAuthorization.class);
    final InspectorEligibilityGate eligibility = mock(InspectorEligibilityGate.class);
    final AssignmentLifecycleRepository assignments = mock(AssignmentLifecycleRepository.class);
    final ReportFileStore files = mock(ReportFileStore.class);
    final ReportFileBindings bindings = mock(ReportFileBindings.class);
    final TransactionRunner tx = mock(TransactionRunner.class);
    final Instant now = Instant.parse("2026-10-25T03:00:00Z");

    UploadLevel15ReportFile service() {
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(tx).inReadCommittedTransaction(any(Runnable.class));
        when(auth.requireMilestone("contract", "milestone")).thenReturn(new InspectionAssignment(
                "commission", "contract", "milestone", "inspector", now.minusSeconds(30), null, "contract-service", null));
        when(eligibility.requireEligibleInspector()).thenReturn("inspector");
        when(files.storeOnBehalfOf(eq("inspector"), eq("key"), eq("application/pdf"), eq(1L), any()))
                .thenReturn(new ReportFileStore.StoredFile("file", "PROCESSING"));
        return new UploadLevel15ReportFile(auth, eligibility, assignments, files, bindings, tx, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void bindsOnlyAfterRecheckingActiveAssignment() {
        var service = service();
        when(assignments.active("contract", "milestone")).thenReturn(Optional.of(new TimedAssignment(
                "commission", "contract", "milestone", "inspector", now.minusSeconds(30), null,
                1, now.plusSeconds(30), null, "contract-service")));
        assertThat(service.upload("contract", "milestone", "key", "application/pdf", 1,
                new ByteArrayInputStream(new byte[]{1})).fileId()).isEqualTo("file");
        verify(bindings).bind("file", "commission", now);
    }

    @Test void endedAssignmentIsNotBoundAfterUpload() {
        var service = service();
        when(assignments.active("contract", "milestone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload("contract", "milestone", "key", "application/pdf", 1,
                new ByteArrayInputStream(new byte[]{1}))).isInstanceOf(InspectionAssignmentAccessDeniedException.class);
        verifyNoInteractions(bindings);
    }
}
