package com.agricontract.reputation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** D52 arithmetic only. Callers must supply independently established multiplier inputs. */
public final class LockDurationPolicy {
    private static final Set<String> LIGHT = Set.of("QUALITY_BELOW_COMMITTED", "SHORTFALL");
    private static final Set<String> STRATEGIC = Set.of("SIDE_SELLING", "NON_DELIVERY", "LATE_DELIVERY",
            "WRONGFUL_REJECTION", "FUNDING_FAILURE", "FAILURE_TO_RECEIVE", "LATE_PAYMENT");

    private LockDurationPolicy() { }

    public static Snapshot calculate(String reason, BigDecimal repeat, BigDecimal trackRecord,
                                     Integer settledMilestones) {
        if (reason != null && reason.isBlank()) throw new IllegalArgumentException("Invalid reason");
        if (repeat == null || trackRecord == null || repeat.signum() <= 0 || trackRecord.signum() <= 0)
            throw new IllegalArgumentException("Missing or invalid multiplier");
        if (settledMilestones != null && settledMilestones < 0)
            throw new IllegalArgumentException("Invalid settled milestone count");
        boolean light = reason != null && LIGHT.contains(reason);
        if (!light && settledMilestones == null)
            throw new IllegalArgumentException("Settled milestone count required for strategic breach");
        int baseDays = light ? 3 : 30;
        BigDecimal zeroProgress = !light && settledMilestones == 0 ? new BigDecimal("1.5") : BigDecimal.ONE;
        int days = BigDecimal.valueOf(baseDays).multiply(repeat).multiply(trackRecord)
                .multiply(zeroProgress).setScale(0, RoundingMode.CEILING).intValueExact();
        return new Snapshot(baseDays, repeat, trackRecord, zeroProgress, days,
                !light && (reason == null || !STRATEGIC.contains(reason)));
    }

    public record Snapshot(int baseDays, BigDecimal repeatOffenseMultiplier, BigDecimal trackRecordMultiplier,
                           BigDecimal zeroProgressMultiplier, int lockDurationDays, boolean unknownReason) { }
}
