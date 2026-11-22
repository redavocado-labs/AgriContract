package com.agricontract.contract.application.exception;

/** file-service did not answer or failed (5xx): nothing was recorded, the client may retry with the same key. */
public class FileServiceUnavailableException extends RuntimeException {
    public FileServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
