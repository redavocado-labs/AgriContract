package com.agricontract.reputation.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LockDurationPolicyTest {
    private static final BigDecimal ONE = BigDecimal.ONE;

    @Test void lightReasonsIgnoreZeroProgressAndRoundUp() {
        for (String reason : new String[]{"QUALITY_BELOW_COMMITTED", "SHORTFALL"}) {
            var snapshot = LockDurationPolicy.calculate(reason, ONE, new BigDecimal("0.7"), 0);
            assertEquals(3, snapshot.baseDays());
            assertEquals(3, snapshot.lockDurationDays());
            assertEquals(0, ONE.compareTo(snapshot.zeroProgressMultiplier()));
            assertFalse(snapshot.unknownReason());
        }
    }

    @Test void strategicAndUnknownReasonsRequireActualProgress() {
        var zero = LockDurationPolicy.calculate("SIDE_SELLING", ONE, ONE, 0);
        assertEquals(45, zero.lockDurationDays());
        assertEquals(30, LockDurationPolicy.calculate("SIDE_SELLING", ONE, ONE, 1).lockDurationDays());
        assertTrue(LockDurationPolicy.calculate("FUTURE_REASON", ONE, ONE, 0).unknownReason());
        assertTrue(LockDurationPolicy.calculate(null, ONE, ONE, 0).unknownReason());
        assertThrows(IllegalArgumentException.class, () -> LockDurationPolicy.calculate("SIDE_SELLING", ONE, ONE, null));
        assertThrows(IllegalArgumentException.class, () -> LockDurationPolicy.calculate("SIDE_SELLING", ONE, ONE, -1));
    }
}
