package com.agricontract.contract.application.exception;

/**
 * escrow.contract_lock_cleared for a contract this service does not know (D93). Non-retryable: the message goes to
 * the DLQ so an operator sees it; a retry would not find the contract either. Never dropped silently.
 */
public class TerminalCommitRefusedException extends RuntimeException {
    public TerminalCommitRefusedException(String message) {
        super(message);
    }
}
