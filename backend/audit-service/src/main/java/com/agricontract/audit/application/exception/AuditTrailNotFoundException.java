package com.agricontract.audit.application.exception;

/**
 * 404 AUDIT_TRAIL_NOT_FOUND (hash-chain §4.6, D97): the contract has no audit record, or the caller is not one of its
 * parties. Both look the same, so the existence of another party's contract is not revealed.
 */
public class AuditTrailNotFoundException extends RuntimeException {
    public AuditTrailNotFoundException(String contractId) {
        super("No evidence log for contract " + contractId);
    }
}
