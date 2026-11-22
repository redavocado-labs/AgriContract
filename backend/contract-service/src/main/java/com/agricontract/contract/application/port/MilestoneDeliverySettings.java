package com.agricontract.contract.application.port;

import java.time.Duration;
import java.util.Objects;

/**
 * Service-wide milestone delivery settings (application.yml, not ContractTerms). weighCorrectionWindow (D75): how
 * long after the first declaration the seller may correct it, 1 hour by default, a few minutes on the demo.
 */
public record MilestoneDeliverySettings(Duration weighCorrectionWindow) {
    public MilestoneDeliverySettings {
        Objects.requireNonNull(weighCorrectionWindow, "weighCorrectionWindow");
        if (weighCorrectionWindow.isNegative() || weighCorrectionWindow.isZero()) {
            throw new IllegalArgumentException("weighCorrectionWindow must be positive");
        }
    }
}
