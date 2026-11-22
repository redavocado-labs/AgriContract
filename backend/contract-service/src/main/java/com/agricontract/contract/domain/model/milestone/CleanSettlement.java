package com.agricontract.contract.domain.model.milestone;

import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Money of a clean milestone (D77), no dispute. S = the seller's declared weight in force, B = the buyer's received
 * weight, t = the contract's toleranceRate, P = the milestone's unit price, L = the locked batch amount.
 * <pre>
 * delta = S - B
 * delta &lt;= t*S : billableKg = S
 * otherwise      : billableKg = S - (delta - t*S) * 0.5
 * sellerAmount = min(L, round_HALF_UP(billableKg * P))      buyerRefund = L - sellerAmount
 * </pre>
 * billableKg is kept exact (up to four decimals). The seller amount is rounded once; the refund is a subtraction.
 */
public final class CleanSettlement {
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private CleanSettlement() {
    }

    public record Result(BigDecimal billableKg, VndAmount sellerAmount, VndAmount buyerRefund) {
    }

    public static Result compute(BigDecimal sellerDeclaredKg, BigDecimal buyerReceivedKg, BigDecimal toleranceRate,
                                 VndAmount unitPrice, VndAmount lockedAmount) {
        Objects.requireNonNull(sellerDeclaredKg, "sellerDeclaredKg");
        Objects.requireNonNull(buyerReceivedKg, "buyerReceivedKg");
        Objects.requireNonNull(toleranceRate, "toleranceRate");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(lockedAmount, "lockedAmount");
        BigDecimal delta = sellerDeclaredKg.subtract(buyerReceivedKg);
        BigDecimal within = toleranceRate.multiply(sellerDeclaredKg);
        BigDecimal billable = delta.compareTo(within) <= 0 ? sellerDeclaredKg
                : sellerDeclaredKg.subtract(delta.subtract(within).multiply(HALF));
        VndAmount owed = VndAmount.ofDong(billable.multiply(unitPrice.value()));
        VndAmount seller = owed.compareTo(lockedAmount) > 0 ? lockedAmount : owed;
        VndAmount refund = new VndAmount(lockedAmount.value().subtract(seller.value()));
        return new Result(billable.stripTrailingZeros(), seller, refund);
    }
}
