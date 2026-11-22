package com.agricontract.inspection.infrastructure.files;

import com.agricontract.inspection.application.usecase.ConfirmLevel15Report;
import com.agricontract.inspection.infrastructure.persistence.JdbcReportFileBindings;

/** D62 rejects an unassociated file before asking the trusted file-service for READY bytes. */
public final class AssignmentVerifiedFiles implements ConfirmLevel15Report.VerifiedFiles {
    private final JdbcReportFileBindings bindings;
    private final FileServiceClient files;
    private final boolean downloadEnabled;

    public static class NotUploaded extends RuntimeException {}

    public AssignmentVerifiedFiles(JdbcReportFileBindings bindings, FileServiceClient files) {
        this(bindings, files, true);
    }

    public AssignmentVerifiedFiles(JdbcReportFileBindings bindings, FileServiceClient files, boolean downloadEnabled) {
        this.bindings = bindings;
        this.files = files;
        this.downloadEnabled = downloadEnabled;
    }

    @Override public byte[] readReady(String commissionId, String fileId) {
        if (!bindings.belongsTo(fileId, commissionId)) throw new NotUploaded();
        if (!downloadEnabled) throw new FileServiceClient.Unavailable("File download integration is unavailable");
        return files.getReady(fileId);
    }
}
