package com.agricontract.bank.infrastructure.web;

import com.agricontract.bank.application.exception.InvalidLedgerQueryException;
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
 * Local handler producing the shared ErrorEnvelope shape. ErrorEnvelopeExceptionHandler itself is not extended: it
 * references Spring Security, which bank-service does not have.
 */
@Slf4j
@RestControllerAdvice
public class BankExceptionHandler {

    @ExceptionHandler(InvalidLedgerQueryException.class)
    public ResponseEntity<ErrorEnvelope> handleInvalidQuery(InvalidLedgerQueryException ex, HttpServletRequest request) {
        return envelope(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleOther(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse framework) { // 404, 405, ... keep their own status
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
