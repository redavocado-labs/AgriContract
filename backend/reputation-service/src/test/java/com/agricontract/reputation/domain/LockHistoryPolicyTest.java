package com.agricontract.reputation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LockHistoryPolicyTest {
    @Test
    void repeatThresholdsAndCalendarMonths() {
        assertEquals(BigDecimal.ONE, LockHistoryPolicy.repeatMultiplier(0));
        assertEquals(BigDecimal.valueOf(2), LockHistoryPolicy.repeatMultiplier(1));
        assertEquals(BigDecimal.valueOf(3), LockHistoryPolicy.repeatMultiplier(2));
        assertEquals(BigDecimal.valueOf(3), LockHistoryPolicy.repeatMultiplier(100));
        assertEquals(Instant.parse("2022-02-28T12:00:00Z"),
                LockHistoryPolicy.repeatWindowStart(Instant.parse("2024-02-29T12:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> LockHistoryPolicy.repeatMultiplier(-1));
    }

    @Test
    void trackRecordUsesExactBoundariesAndMinimumCount() {
        assertEquals(BigDecimal.ONE, LockHistoryPolicy.trackRecordMultiplier(4, 0));
        assertEquals(new BigDecimal("0.7"), LockHistoryPolicy.trackRecordMultiplier(9, 1));
        assertEquals(BigDecimal.ONE, LockHistoryPolicy.trackRecordMultiplier(7, 3));
        assertEquals(new BigDecimal("1.3"), LockHistoryPolicy.trackRecordMultiplier(6, 4));
        assertEquals(BigDecimal.ONE, LockHistoryPolicy.trackRecordMultiplier(8, 2));
        assertThrows(IllegalArgumentException.class, () -> LockHistoryPolicy.trackRecordMultiplier(5, -1));
    }

    @Test
    void incompleteEarlierHistoryChangesTheImmutableSnapshot() {
        assertEquals(BigDecimal.ONE, LockHistoryPolicy.repeatMultiplier(0));
        assertEquals(BigDecimal.valueOf(2), LockHistoryPolicy.repeatMultiplier(1));
        assertEquals(BigDecimal.ONE, LockHistoryPolicy.trackRecordMultiplier(4, 0));
        assertEquals(new BigDecimal("0.7"), LockHistoryPolicy.trackRecordMultiplier(5, 0));
    }
}
