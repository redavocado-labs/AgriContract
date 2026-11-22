package com.agricontract.contract.domain.model.milestone;

import java.math.BigDecimal;

/**
 * Bounds of a weighed quantity (D72, design §2.1): canonical kilograms, more than 0, at most 1,000,000, at most
 * three decimals (grams). The real figure is kept even above committedQuantity; the money stays capped at the
 * locked batch amount.
 */
public final class SellerWeight {
    public static final BigDecimal MAX_KG = new BigDecimal("1000000");

    private SellerWeight() {
    }

    public static BigDecimal requireValid(BigDecimal weight) {
        if (weight == null) {
            throw new IllegalArgumentException("The weight is required.");
        }
        if (weight.signum() <= 0 || weight.compareTo(MAX_KG) > 0) {
            throw new IllegalArgumentException("The weight must be more than 0 and at most 1,000,000 kg.");
        }
        if (weight.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("The weight has at most three decimals (grams).");
        }
        return weight;
    }

    /** Plain decimal with no trailing zeros, e.g. 9500.5 or 1000: the figure shown, hashed and published. */
    public static String plain(BigDecimal weight) {
        return weight.stripTrailingZeros().toPlainString();
    }
}
