package com.agricontract.escrow.application.exception;

/** bank-service could not answer the ledger read (unreachable, 5xx, unreadable body). Retryable: never dispatch without it. */
public class BankLedgerUnavailableException extends RuntimeException {
    public BankLedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
