package com.agricontract.inspection.infrastructure.files;

import com.agricontract.inspection.infrastructure.persistence.JdbcReportFileBindings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentVerifiedFilesTest {
    @Test void disabledDownloadFailsClosedAfterAssociationCheck() {
        var bindings = mock(JdbcReportFileBindings.class);
        var files = mock(FileServiceClient.class);
        when(bindings.belongsTo("file", "commission")).thenReturn(true);
        assertThatThrownBy(() -> new AssignmentVerifiedFiles(bindings, files, false).readReady("commission", "file"))
                .isInstanceOf(FileServiceClient.Unavailable.class);
        verifyNoInteractions(files);
    }

    @Test void rejectsWrongAssignmentBeforeCallingFileService() {
        var bindings = mock(JdbcReportFileBindings.class);
        var files = mock(FileServiceClient.class);
        var verified = new AssignmentVerifiedFiles(bindings, files);
        assertThatThrownBy(() -> verified.readReady("other-commission", "file"))
                .isInstanceOf(AssignmentVerifiedFiles.NotUploaded.class);
        verifyNoInteractions(files);
    }

    @Test void returnsOnlyBytesFromFileServiceForAssociatedFile() {
        var bindings = mock(JdbcReportFileBindings.class);
        var files = mock(FileServiceClient.class);
        when(bindings.belongsTo("file", "commission")).thenReturn(true);
        when(files.getReady("file")).thenReturn(new byte[]{1, 2});
        assertThat(new AssignmentVerifiedFiles(bindings, files).readReady("commission", "file"))
                .containsExactly(1, 2);
    }
}
