package com.agricontract.reputation.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/** D55 calculations over historical counts supplied by the caller. */
public final class LockHistoryPolicy {
    private LockHistoryPolicy() { }

    public static Instant repeatWindowStart(Instant decidedAt) {
        return Objects.requireNonNull(decidedAt).atOffset(ZoneOffset.UTC).minusMonths(24).toInstant();
    }

    public static BigDecimal repeatMultiplier(long priorLocks) {
        if (priorLocks < 0) {
            throw new IllegalArgumentException("Negative lock count");
        }
        return BigDecimal.valueOf(priorLocks == 0 ? 1 : priorLocks == 1 ? 2 : 3);
    }

    public static BigDecimal trackRecordMultiplier(long completed, long priorLocks) {
        if (completed < 0 || priorLocks < 0) {
            throw new IllegalArgumentException("Negative history count");
        }
        if (completed < 5) {
            return BigDecimal.ONE;
        }
        BigInteger clean = BigInteger.valueOf(completed).multiply(BigInteger.TEN);
        BigInteger total = BigInteger.valueOf(completed).add(BigInteger.valueOf(priorLocks));
        if (clean.compareTo(total.multiply(BigInteger.valueOf(9))) >= 0) {
            return new BigDecimal("0.7");
        }
        if (clean.compareTo(total.multiply(BigInteger.valueOf(7))) >= 0) {
            return BigDecimal.ONE;
        }
        return new BigDecimal("1.3");
    }
}
