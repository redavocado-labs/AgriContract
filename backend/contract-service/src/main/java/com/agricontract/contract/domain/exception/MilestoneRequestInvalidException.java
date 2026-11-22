package com.agricontract.contract.domain.exception;

/** A milestone request that is wrong in itself (HTTP 400), with the code the client branches on. */
public class MilestoneRequestInvalidException extends RuntimeException {
    /** D71: a named file was not uploaded by this party for this milestone through uploadMilestoneEvidence. */
    public static final String EVIDENCE_NOT_UPLOADED = "EVIDENCE_NOT_UPLOADED";
    /** D71: the weighing photo and the delivery certificate are the same file. */
    public static final String EVIDENCE_FILE_REUSED = "EVIDENCE_FILE_REUSED";

    private final String code;

    public MilestoneRequestInvalidException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
