package com.agricontract.contract.application.exception;

/**
 * The upload itself is not acceptable: 400 (type not in the whitelist, missing or wrong Content-Length, body shorter
 * or longer than declared) or 413 FILE_TOO_LARGE (over 10 MiB). Checked by contract-service before a byte is read, and
 * passed on as is when file-service refuses the body.
 */
public class FileRejectedException extends RuntimeException {
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";

    private final int status;
    private final String code;

    public FileRejectedException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
