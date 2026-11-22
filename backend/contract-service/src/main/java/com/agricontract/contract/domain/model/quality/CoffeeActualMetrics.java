package com.agricontract.contract.domain.model.quality;

import com.agricontract.contract.domain.model.terms.CoffeeType;

import java.math.BigDecimal;

/** Measured values of one inspection: percentages in 0..100 with at most two decimals. */
public record CoffeeActualMetrics(
        CoffeeType type,
        BigDecimal moisturePercent,
        BigDecimal foreignMatterPercent,
        BigDecimal blackBrokenBeansPercent) {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public CoffeeActualMetrics {
        if (type == null) {
            throw new InvalidQualityInputException("type is required");
        }
        check("moisturePercent", moisturePercent);
        check("foreignMatterPercent", foreignMatterPercent);
        check("blackBrokenBeansPercent", blackBrokenBeansPercent);
    }

    private static void check(String name, BigDecimal v) {
        if (v == null) {
            throw new InvalidQualityInputException(name + " is required");
        }
        if (v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
            throw new InvalidQualityInputException(name + " must be within 0..100: " + v.toPlainString());
        }
        if (v.stripTrailingZeros().scale() > 2) {
            throw new InvalidQualityInputException(name + " has more than 2 decimals: " + v.toPlainString());
        }
    }
}
