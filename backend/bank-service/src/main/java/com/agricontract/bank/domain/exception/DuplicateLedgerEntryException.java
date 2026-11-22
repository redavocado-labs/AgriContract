package com.agricontract.bank.domain.exception;

// Another delivery won the insert race on source_event_id or remedy_leg_id; the entry it wrote is the result.
public class DuplicateLedgerEntryException extends RuntimeException {

    public DuplicateLedgerEntryException(String sourceEventId) {
        super("Ledger entry already recorded for sourceEventId " + sourceEventId);
    }

    public static DuplicateLedgerEntryException forRemedyLeg(String remedyLegId) {
        return new DuplicateLedgerEntryException("remedyLegId", remedyLegId);
    }

    private DuplicateLedgerEntryException(String key, String value) {
        super("Ledger entry already recorded for " + key + " " + value);
    }
}
