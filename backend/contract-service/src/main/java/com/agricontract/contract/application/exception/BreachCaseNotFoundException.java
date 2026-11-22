package com.agricontract.contract.application.exception;

/** No such breach case on this contract (404). */
public class BreachCaseNotFoundException extends RuntimeException {
    public BreachCaseNotFoundException(String contractId, String breachCaseId) {
        super("Breach case " + breachCaseId + " not found on contract " + contractId);
    }
}
