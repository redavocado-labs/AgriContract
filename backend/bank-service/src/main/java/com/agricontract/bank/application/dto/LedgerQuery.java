package com.agricontract.bank.application.dto;

import java.time.Instant;

/** GET /internal/v1/bank/ledger query, already parsed; every field is optional here, the use case checks the combination. */
public record LedgerQuery(String contractId, String participantId, String remedyDecisionId, Instant from, Instant to) {
}
