package com.agricontract.audit.domain.exception;

/** The identity of an audit record breaks a domain rule. Retrying cannot fix it. */
public class InvalidAuditRecordException extends RuntimeException {

    public InvalidAuditRecordException(String message) {
        super(message);
    }
}
