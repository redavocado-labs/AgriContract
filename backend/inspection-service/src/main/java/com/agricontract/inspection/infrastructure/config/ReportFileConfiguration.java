package com.agricontract.inspection.infrastructure.config;

import com.agricontract.inspection.application.port.ReportFileBindings;
import com.agricontract.inspection.application.port.ReportFileStore;
import com.agricontract.inspection.application.port.TransactionRunner;
import com.agricontract.inspection.application.usecase.InspectionAuthorization;
import com.agricontract.inspection.application.usecase.InspectorEligibilityGate;
import com.agricontract.inspection.application.usecase.UploadLevel15ReportFile;
import com.agricontract.inspection.application.usecase.ConfirmLevel15Report;
import com.agricontract.inspection.application.usecase.Level15ReportPersistence;
import com.agricontract.inspection.application.usecase.ManageLevel15Assignments;
import com.agricontract.inspection.domain.service.InspectionCanonicalizer;
import com.agricontract.inspection.infrastructure.files.AssignmentVerifiedFiles;
import com.agricontract.inspection.infrastructure.security.AuthenticatedSession;
import com.agricontract.inspection.domain.repository.AssignmentLifecycleRepository;
import com.agricontract.inspection.infrastructure.files.FileServiceClient;
import com.agricontract.inspection.infrastructure.files.FileAttachmentRecovery;
import com.agricontract.inspection.infrastructure.persistence.JdbcReportFileBindings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class ReportFileConfiguration {
    @Bean FileServiceClient inspectionFileServiceClient(
            @Value("${inspection.file-service.base-url:http://localhost:8089}") URI base,
            @Value("${SERVICE_INTERNAL_SECRET}") String secret,
            @Value("${inspection.file-service.connect-timeout-ms:2000}") int connectTimeout,
            @Value("${inspection.file-service.read-timeout-ms:10000}") int readTimeout) {
        return new FileServiceClient(base, secret, connectTimeout, readTimeout);
    }

    @Bean UploadLevel15ReportFile uploadLevel15ReportFile(InspectionAuthorization auth, InspectorEligibilityGate eligibility,
            AssignmentLifecycleRepository assignments, ReportFileStore files, ReportFileBindings bindings,
            TransactionRunner transactions, Clock clock) {
        return new UploadLevel15ReportFile(auth, eligibility, assignments, files, bindings, transactions, clock);
    }

    @Bean FileAttachmentRecovery fileAttachmentRecovery(JdbcReportFileBindings bindings, FileServiceClient files,
            TransactionRunner transactions, Clock clock) {
        return new FileAttachmentRecovery(bindings, files, transactions, clock);
    }

    @Bean ConfirmLevel15Report confirmLevel15Report(Level15ReportPersistence persistence,
            AssignmentLifecycleRepository assignments, ManageLevel15Assignments lifecycle, TransactionRunner transactions,
            InspectionCanonicalizer canonicalizer, AuthenticatedSession session, JdbcReportFileBindings bindings,
            FileServiceClient files, ConfirmLevel15Report.ConfirmationWrites writes, Clock clock,
            @Value("${inspection.file-service.download-enabled:true}") boolean downloadEnabled,
            @Value("${inspection.auth-max-age-seconds:1800}") long maxAgeSeconds) {
        return new ConfirmLevel15Report(persistence, assignments, lifecycle, transactions, canonicalizer,
                session, new AssignmentVerifiedFiles(bindings, files, downloadEnabled), writes, clock, Duration.ofSeconds(maxAgeSeconds));
    }
}
