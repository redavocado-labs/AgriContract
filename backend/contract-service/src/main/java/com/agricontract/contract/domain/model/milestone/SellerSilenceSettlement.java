package com.agricontract.contract.domain.model.milestone;

import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Money of a QUANTITY flag the seller left unanswered (D82): the milestone is settled on the buyer's weight, with no
 * tolerance. B = the buyer's received weight, P = the milestone's unit price, L = the locked batch amount.
 * <pre>
 * billableKg = B
 * sellerAmount = min(L, round_HALF_UP(B * P))      buyerRefund = L - sellerAmount
 * </pre>
 */
public final class SellerSilenceSettlement {
    private SellerSilenceSettlement() {
    }

    public static CleanSettlement.Result compute(BigDecimal buyerReceivedKg, VndAmount unitPrice,
                                                 VndAmount lockedAmount) {
        Objects.requireNonNull(buyerReceivedKg, "buyerReceivedKg");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(lockedAmount, "lockedAmount");
        VndAmount owed = VndAmount.ofDong(buyerReceivedKg.multiply(unitPrice.value()));
        VndAmount seller = owed.compareTo(lockedAmount) > 0 ? lockedAmount : owed;
        VndAmount refund = new VndAmount(lockedAmount.value().subtract(seller.value()));
        return new CleanSettlement.Result(buyerReceivedKg.stripTrailingZeros(), seller, refund);
    }
}
