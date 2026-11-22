package com.agricontract.contract.application.exception;

/** No such milestone on this contract (404). Checked only after the caller is known to be a party (BOLA). */
public class MilestoneNotFoundException extends RuntimeException {
    public MilestoneNotFoundException(String contractId, String milestoneId) {
        super("Milestone " + milestoneId + " not found on contract " + contractId);
    }
}
