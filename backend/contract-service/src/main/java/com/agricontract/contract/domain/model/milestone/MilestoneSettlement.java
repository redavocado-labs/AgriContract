package com.agricontract.contract.domain.model.milestone;

import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of settling a milestone (D77): the quantity paid for, what the seller gets and what goes back to the
 * buyer. sellerAmount + buyerRefundAmount is the locked batch amount.
 */
public record MilestoneSettlement(SettlementPath path, BigDecimal billableQuantityKg, VndAmount sellerAmount,
                                  VndAmount buyerRefundAmount, Instant settledAt) {
    public MilestoneSettlement {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(billableQuantityKg, "billableQuantityKg");
        Objects.requireNonNull(sellerAmount, "sellerAmount");
        Objects.requireNonNull(buyerRefundAmount, "buyerRefundAmount");
        Objects.requireNonNull(settledAt, "settledAt");
    }
}
