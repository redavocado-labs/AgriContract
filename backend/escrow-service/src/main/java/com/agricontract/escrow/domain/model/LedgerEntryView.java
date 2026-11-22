package com.agricontract.escrow.domain.model;

import java.math.BigDecimal;

/** The fields of a bank ledger entry the conservation check reads (bank GET /internal/v1/bank/ledger). */
public record LedgerEntryView(String entryType, String fundType, String milestoneId, BigDecimal amount) {

    public boolean isLock() {
        return entryType.startsWith("LOCK_");
    }

    public FundPool pool() {
        return FundPool.of(fundType, milestoneId);
    }
}
