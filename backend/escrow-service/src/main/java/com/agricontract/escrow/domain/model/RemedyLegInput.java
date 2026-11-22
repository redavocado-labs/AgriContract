package com.agricontract.escrow.domain.model;

import java.math.BigDecimal;

/** One remedyLegs[] item of remedy.finalized as received; enums are still text, the planner validates them. */
public record RemedyLegInput(String remedyLegId, String remedyType, String fundType, String role,
                             BigDecimal amount) {
}
