package com.agricontract.bank.domain.repository;

import java.time.Instant;

/** Read filter over the ledger; a null field does not filter. Filters AND together; from inclusive, to exclusive. */
public record LedgerEntryFilter(String contractId, String participantId, String remedyDecisionId,
                                Instant from, Instant to) {
}
