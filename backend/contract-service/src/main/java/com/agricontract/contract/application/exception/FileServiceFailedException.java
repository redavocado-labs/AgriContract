package com.agricontract.contract.application.exception;

/**
 * file-service answered something outside its contract (an unexpected 4xx, 3xx or an unreadable body): retrying the
 * same request cannot help, so this is not a 503. The detail stays in the log.
 */
public class FileServiceFailedException extends RuntimeException {
    public FileServiceFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
