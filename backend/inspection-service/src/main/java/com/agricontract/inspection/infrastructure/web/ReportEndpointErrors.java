package com.agricontract.inspection.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {ReportFileUploadController.class, ReportSubmissionController.class})
public class ReportEndpointErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> error(ResponseStatusException failure, HttpServletRequest request) {
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
        return ResponseEntity.status(status).body(Map.of("timestamp", Instant.now().toString(), "status", status,
                "error", http == null ? "Error" : http.getReasonPhrase(), "code", code,
                "message", message, "path", request.getRequestURI(), "traceId", UUID.randomUUID().toString()));
    }
}
