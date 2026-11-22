package com.agricontract.contract.application.exception;

/** No such evidence file recorded for this milestone (404). Never looked up in file-service. */
public class EvidenceFileNotFoundException extends RuntimeException {
    public EvidenceFileNotFoundException(String milestoneId, String fileId) {
        super("File " + fileId + " is not evidence of milestone " + milestoneId);
    }
}
