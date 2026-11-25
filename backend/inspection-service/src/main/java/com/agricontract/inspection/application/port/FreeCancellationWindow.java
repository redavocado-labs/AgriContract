package com.agricontract.inspection.application.port;

import java.time.Duration;
import java.util.Objects;

/** The configured free cancellation interval for newly created assignments. */
public record FreeCancellationWindow(Duration duration) {
    public FreeCancellationWindow {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("Positive cancellation window required");
    }
}
