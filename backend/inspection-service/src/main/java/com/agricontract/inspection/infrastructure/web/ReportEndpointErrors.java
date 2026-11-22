package com.agricontract.inspection.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import com.agricontract.web.error.ErrorEnvelope;
import com.agricontract.web.error.ErrorEnvelopeExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {ReportFileUploadController.class, ReportSubmissionController.class})
public class ReportEndpointErrors extends ErrorEnvelopeExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorEnvelope> error(ResponseStatusException failure, HttpServletRequest request) {
        int status = failure.getStatusCode().value();
        String reason = failure.getReason();
        String code = reason != null && reason.matches("[A-Z][A-Z0-9_]*") ? reason : switch (status) {
            case 403 -> "FORBIDDEN";
            case 409 -> "REPORT_STATE_CONFLICT";
            case 503 -> "DEPENDENCY_UNAVAILABLE";
            default -> "VALIDATION_ERROR";
        };
        String message = reason == null ? "Request failed" : reason;
        var http = HttpStatus.resolve(status);
        return envelope(http == null ? HttpStatus.INTERNAL_SERVER_ERROR : http, code, message, request);
    }
}
