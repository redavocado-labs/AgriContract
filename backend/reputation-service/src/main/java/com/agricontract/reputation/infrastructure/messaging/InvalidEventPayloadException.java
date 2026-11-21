package com.agricontract.reputation.infrastructure.messaging;

/** Non-retryable malformed or contract-invalid event; messages must not contain payload values. */
public class InvalidEventPayloadException extends RuntimeException {
    public InvalidEventPayloadException(String message) {
        super(message);
    }
}
