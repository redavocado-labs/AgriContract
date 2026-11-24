package com.agricontract.reputation.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LockDurationPolicyTest {
    private static final BigDecimal ONE = BigDecimal.ONE;

    @Test void lightReasonsIgnoreZeroProgressAndRoundUp() {
        for (String reason : new String[]{"QUALITY_BELOW_COMMITTED", "SHORTFALL"}) {
            var snapshot = LockDurationPolicy.calculate(reason, ONE, new BigDecimal("0.7"), true);
            assertEquals(3, snapshot.baseDays());
            assertEquals(3, snapshot.lockDurationDays());
            assertEquals(0, ONE.compareTo(snapshot.zeroProgressMultiplier()));
            assertFalse(snapshot.unknownReason());
        }
    }

    @Test void zeroProgressAndMultipliersPreserveCeiling() {
        for (String repeat : new String[]{"1", "2", "3"}) {
            for (String track : new String[]{"0.7", "1", "1.3"}) {
                var r = new BigDecimal(repeat);
                var t = new BigDecimal(track);
                for (boolean zero : new boolean[]{true, false}) {
                    for (String reason : new String[]{"SIDE_SELLING", "SHORTFALL"}) {
                        boolean strategic = reason.equals("SIDE_SELLING");
                        var factor = strategic && zero ? new BigDecimal("1.5") : ONE;
                        var result = LockDurationPolicy.calculate(reason, r, t, zero);
                        assertEquals(0, factor.compareTo(result.zeroProgressMultiplier()));
                        int expected = BigDecimal.valueOf(strategic ? 30 : 3).multiply(r).multiply(t)
                                .multiply(factor).setScale(0, java.math.RoundingMode.CEILING).intValueExact();
                        assertEquals(expected, result.lockDurationDays());
                    }
                }
            }
        }
    }
    @Test void strategicAndUnknownReasonsUseZeroProgress() {
        var zero = LockDurationPolicy.calculate("SIDE_SELLING", ONE, ONE, true);
        assertEquals(45, zero.lockDurationDays());
        assertEquals(30, LockDurationPolicy.calculate("SIDE_SELLING", ONE, ONE, false).lockDurationDays());
        assertTrue(LockDurationPolicy.calculate("FUTURE_REASON", ONE, ONE, true).unknownReason());
        assertTrue(LockDurationPolicy.calculate(null, ONE, ONE, true).unknownReason());
        assertEquals(3, LockDurationPolicy.calculate("SHORTFALL", ONE, ONE, false).lockDurationDays());
    }
}
