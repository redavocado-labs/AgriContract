package com.agricontract.audit.infrastructure.messaging;

// The message cannot be read as the source event it claims to be. Retrying cannot fix it, so it is registered as
// non-retryable and goes straight to the DLQ. The message never includes the payload.
public class InvalidEventPayloadException extends RuntimeException {

    public InvalidEventPayloadException(String message) {
        super(message);
    }
}
