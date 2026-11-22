package com.agricontract.inspection.infrastructure.web;

import com.agricontract.inspection.application.exception.EligibilityUnavailableException;
import com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException;
import com.agricontract.inspection.application.exception.InspectorNotEligibleException;
import com.agricontract.inspection.application.port.ReportFileStore;
import com.agricontract.inspection.application.port.ReportFileBindings;
import com.agricontract.inspection.application.usecase.UploadLevel15ReportFile;
import com.agricontract.inspection.infrastructure.files.FileServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "inspection.report-endpoints-enabled", havingValue = "true", matchIfMissing = true)
public class ReportFileUploadController {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    private final UploadLevel15ReportFile upload;

    public ReportFileUploadController(UploadLevel15ReportFile upload) { this.upload = upload; }

    @PostMapping("/api/v1/inspections/{contractId}/milestones/{milestoneId}/report-files")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReportFileStore.StoredFile upload(@PathVariable UUID contractId, @PathVariable UUID milestoneId,
                                              HttpServletRequest request) throws IOException {
        String key = singleHeader(request, "Idempotency-Key");
        String rawType = singleHeader(request, "Content-Type");
        String type = null;
        if (rawType != null) {
            try {
                var parsed = org.springframework.http.MediaType.parseMediaType(rawType);
                type = (parsed.getType() + "/" + parsed.getSubtype()).toLowerCase(java.util.Locale.ROOT);
            } catch (org.springframework.http.InvalidMediaTypeException invalid) { /* validation below */ }
        }
        String lengthHeader = singleHeader(request, "Content-Length");
        if (request.getHeader("Transfer-Encoding") != null || key == null || !key.matches("[\\x20-\\x7e]{1,255}") || type == null || !TYPES.contains(type)
                || lengthHeader == null || !lengthHeader.matches("[0-9]{1,12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        long length = Long.parseLong(lengthHeader);
        if (length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        if (length < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        var body = request.getInputStream();
        try {
            return upload.upload(contractId.toString(), milestoneId.toString(), key, type, length, body);
        } catch (InspectionAssignmentAccessDeniedException | InspectorNotEligibleException denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No authorized inspection assignment");
        } catch (ReportFileBindings.BindingConflict conflict) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        } catch (FileServiceClient.InvalidUpload invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        } catch (EligibilityUnavailableException | FileServiceClient.Unavailable unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        } finally {
            // A replay or failed upstream call may leave part of the bounded request unread.
            try { body.skipNBytes(length); } catch (IOException ignored) { /* no more bytes or connection closed */ }
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }
}
