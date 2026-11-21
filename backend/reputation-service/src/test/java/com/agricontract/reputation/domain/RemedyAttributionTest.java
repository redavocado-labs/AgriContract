package com.agricontract.reputation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemedyAttributionTest {
    private final UUID buyer = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();

    @Test
    void eligibleRoleDeterminesExactlyOneUser() {
        assertEquals(buyer, RemedyAttribution.select(true, RemedyAttribution.Role.BUYER,
                buyer, seller, "SHORTFALL").orElseThrow().userId());
        assertEquals(seller, RemedyAttribution.select(true, RemedyAttribution.Role.SELLER,
                buyer, seller, "QUALITY_BELOW_COMMITTED").orElseThrow().userId());
    }

    @Test
    void noFaultAndIneligibleDecisionsDoNotSelectAUser() {
        assertFalse(RemedyAttribution.select(false, null, buyer, seller, null).isPresent());
        assertFalse(RemedyAttribution.select(false, RemedyAttribution.Role.BUYER,
                buyer, seller, "FUNDING_FAILURE").isPresent());
    }

    @Test
    void eligibleRemedyMustHaveReason() {
        assertThrows(IllegalArgumentException.class, () -> RemedyAttribution.select(true,
                RemedyAttribution.Role.BUYER, buyer, seller, null));
    }
}
