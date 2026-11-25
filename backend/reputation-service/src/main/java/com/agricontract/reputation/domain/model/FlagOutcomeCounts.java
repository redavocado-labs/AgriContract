package com.agricontract.reputation.domain.model;

/** Counts of finalized buyer flag outcomes, without an independently stored rate. */
public record FlagOutcomeCounts(long unsuccessful, long total) {
    public FlagOutcomeCounts {
        if (unsuccessful < 0 || total < unsuccessful) throw new IllegalArgumentException("Invalid flag counts");
    }
}
