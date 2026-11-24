package com.agricontract.audit.infrastructure.web;

import com.agricontract.audit.application.exception.AuditTrailNotFoundException;
import com.agricontract.web.error.ErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared ErrorEnvelope shape without Spring Security (ErrorEnvelopeExceptionHandler references it), as
 * bank-service. A 5xx never carries the exception text.
 */
@Slf4j
@RestControllerAdvice
public class AuditExceptionHandler {

    @ExceptionHandler(AuditTrailNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> notFound(AuditTrailNotFoundException ex, HttpServletRequest request) {
        return envelope(HttpStatus.NOT_FOUND, "AUDIT_TRAIL_NOT_FOUND", "No evidence log for this contract", request);
    }

    @ExceptionHandler(AuditApiException.class)
    public ResponseEntity<ErrorEnvelope> api(AuditApiException ex, HttpServletRequest request) {
        return envelope(ex.status, ex.code, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> other(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse framework) {
            HttpStatusCode code = framework.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            if (status != null && code.is4xxClientError()) {
                return envelope(status, "REQUEST_REJECTED", status.getReasonPhrase(), request);
            }
        }
        ResponseEntity<ErrorEnvelope> response =
                envelope(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", request);
        log.error("Unhandled exception on {} traceId={}", request.getRequestURI(), response.getBody().traceId(), ex);
        return response;
    }

    private static ResponseEntity<ErrorEnvelope> envelope(HttpStatus status, String code, String message,
                                                          HttpServletRequest request) {
        String correlation = request.getHeader("X-Correlation-Id");
        return ResponseEntity.status(status).body(new ErrorEnvelope(Instant.now(), status.value(),
                status.getReasonPhrase(), code, message, request.getRequestURI(),
                StringUtils.hasText(correlation) ? correlation : UUID.randomUUID().toString()));
    }
}
