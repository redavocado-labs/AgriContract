package com.agricontract.reputation.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Maps an already validated final attribution to the single penalized party. */
public final class RemedyAttribution {
    public enum Role { BUYER, SELLER }

    public record PenalizedParty(UUID userId, Role role, String reason) { }

    private RemedyAttribution() { }

    public static Optional<PenalizedParty> select(boolean reputationEligible, Role finalRole,
                                                   UUID buyerId, UUID sellerId, String reason) {
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(sellerId, "sellerId");
        if (reputationEligible && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Eligible remedy requires a breach reason");
        }
        if (!reputationEligible || finalRole == null) {
            return Optional.empty();
        }
        UUID user = finalRole == Role.BUYER ? buyerId : sellerId;
        return Optional.of(new PenalizedParty(user, finalRole, reason));
    }
}
