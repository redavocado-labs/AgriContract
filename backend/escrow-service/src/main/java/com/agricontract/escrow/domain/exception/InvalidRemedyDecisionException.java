package com.agricontract.escrow.domain.exception;

/** A remedy.finalized leg set escrow must not turn into bank commands: the whole decision is rejected (#249). */
public class InvalidRemedyDecisionException extends RuntimeException {
    public InvalidRemedyDecisionException(String message) {
        super(message);
    }
}
