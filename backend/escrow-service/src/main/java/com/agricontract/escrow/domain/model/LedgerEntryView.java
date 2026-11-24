package com.agricontract.escrow.domain.model;

import java.math.BigDecimal;

/** The fields of a bank ledger entry the conservation check reads (bank GET /internal/v1/bank/ledger). */
public record LedgerEntryView(String entryType, String fundType, String milestoneId, BigDecimal amount) {

    public boolean isLock() {
        return entryType.startsWith("LOCK_");
    }

    /** What the entry does to its pool's remaining lock: a LOCK_* adds, every other entry type moves money out. */
    public BigDecimal signedAmount() {
        return isLock() ? amount : amount.negate();
    }

    public FundPool pool() {
        return FundPool.of(fundType, milestoneId);
    }
}
