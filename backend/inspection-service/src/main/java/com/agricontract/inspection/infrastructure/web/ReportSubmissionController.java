package com.agricontract.inspection.infrastructure.web;

import com.agricontract.inspection.application.exception.EligibilityUnavailableException;
import com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException;
import com.agricontract.inspection.application.exception.InspectorNotEligibleException;
import com.agricontract.inspection.application.port.ReportSubmissionIdempotency;
import com.agricontract.inspection.application.usecase.ConfirmLevel15Report;
import com.agricontract.inspection.application.usecase.InspectorEligibilityGate;
import com.agricontract.inspection.infrastructure.files.AssignmentVerifiedFiles;
import com.agricontract.inspection.infrastructure.files.FileAttachmentRecovery;
import com.agricontract.inspection.infrastructure.files.FileServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "inspection.report-endpoints-enabled", havingValue = "true", matchIfMissing = true)
public class ReportSubmissionController {
    private final ConfirmLevel15Report confirmation;
    private final InspectorEligibilityGate eligibility;
    private final ReportSubmissionIdempotency receipts;
    private final FileAttachmentRecovery recovery;
    private final InspectionReportSubmissionParser parser = new InspectionReportSubmissionParser();

    public ReportSubmissionController(ConfirmLevel15Report confirmation, InspectorEligibilityGate eligibility,
            ReportSubmissionIdempotency receipts, FileAttachmentRecovery recovery) {
        this.confirmation = confirmation; this.eligibility = eligibility; this.receipts = receipts; this.recovery = recovery;
    }

    @PostMapping("/api/v1/inspections/{contractId}/milestones/{milestoneId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public void submit(@PathVariable UUID contractId, @PathVariable UUID milestoneId,
                       @RequestBody String json, HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.getFirst().matches("[\\x20-\\x7e]{1,255}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        try {
            var input = parser.parse(json);
            confirmation.confirmIdempotent(contractId.toString(), milestoneId.toString(), input.reportFileId(),
                    json, input.normalizedResult(), keys.getFirst(), eligibility, receipts);
            recovery.confirmOne(input.reportFileId());
        } catch (ConfirmLevel15Report.FreshAuthenticationRequired stale) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fresh authentication required");
        } catch (InspectionAssignmentAccessDeniedException | InspectorNotEligibleException denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No authorized inspection assignment");
        } catch (AssignmentVerifiedFiles.NotUploaded invalidFile) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "REPORT_FILE_NOT_UPLOADED");
        } catch (FileServiceClient.NotReady notReady) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPORT_FILE_NOT_READY");
        } catch (ConfirmLevel15Report.IdempotencyConflict conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPORT_STATE_CONFLICT");
        } catch (ConfirmLevel15Report.AlreadySigned signed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPORT_ALREADY_SIGNED");
        } catch (EligibilityUnavailableException | FileServiceClient.Unavailable unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }
}
