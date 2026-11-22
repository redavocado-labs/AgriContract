package com.agricontract.bank.application.exception;

// The ledger read query breaks the frozen validation rules; answered as 400 VALIDATION_ERROR.
public class InvalidLedgerQueryException extends RuntimeException {

    public InvalidLedgerQueryException(String message) {
        super(message);
    }
}
