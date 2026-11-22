package com.agricontract.inspection.infrastructure.files;

import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.infrastructure.persistence.JdbcReportFileBindings;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileAttachmentRecoveryTest {
    @Test void confirmOneOnlyAcknowledgesAReportedFileAfterRemoteSuccess() {
        var bindings = mock(JdbcReportFileBindings.class);
        var files = mock(FileServiceClient.class);
        var transactions = mock(TransactionRunner.class);
        when(transactions.inTransaction(any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var recovery = new FileAttachmentRecovery(bindings, files, transactions, Clock.systemUTC());
        recovery.confirmOne("unused");
        verifyNoInteractions(files);
        when(bindings.isPendingAttachment("reported")).thenReturn(true);
        doThrow(new FileServiceClient.Unavailable("offline")).doNothing().when(files).confirmAttached("reported");
        recovery.confirmOne("reported");
        verify(bindings, never()).markAttached(eq("reported"), any());
        recovery.confirmOne("reported");
        verify(bindings).markAttached(eq("reported"), any());
    }

    @Test void refusesToCallFileServiceInsideAnExistingTransaction() {
        var bindings = mock(JdbcReportFileBindings.class);
        var files = mock(FileServiceClient.class);
        var recovery = new FileAttachmentRecovery(bindings, files, mock(TransactionRunner.class), Clock.systemUTC());
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> recovery.retryBatch(10)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(bindings, files);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
    }

    @Test void failedRemoteConfirmationRemainsPendingAndCanBeRetried() {
        var bindings = mock(JdbcReportFileBindings.class);
        var files = mock(FileServiceClient.class);
        var transactions = mock(TransactionRunner.class);
        when(bindings.pendingAttachments(10)).thenReturn(List.of("file"));
        doThrow(new FileServiceClient.Unavailable("offline")).doNothing().when(files).confirmAttached("file");
        when(transactions.inTransaction(any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var recovery = new FileAttachmentRecovery(bindings, files, transactions,
                Clock.fixed(Instant.parse("2026-10-25T03:00:00Z"), ZoneOffset.UTC));
        assertThat(recovery.retryBatch(10)).isZero();
        verify(bindings, never()).markAttached(anyString(), any());
        assertThat(recovery.retryBatch(10)).isEqualTo(1);
        verify(bindings).markAttached(eq("file"), any());
    }
}
