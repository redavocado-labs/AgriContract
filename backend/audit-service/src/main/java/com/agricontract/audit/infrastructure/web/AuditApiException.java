package com.agricontract.audit.infrastructure.web;

import org.springframework.http.HttpStatus;

/** 400 VALIDATION_ERROR and 403 FORBIDDEN of the evidence log API. */
class AuditApiException extends RuntimeException {
    final HttpStatus status;
    final String code;

    AuditApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static AuditApiException invalid(String message) {
        return new AuditApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    static AuditApiException forbidden(String message) {
        return new AuditApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
