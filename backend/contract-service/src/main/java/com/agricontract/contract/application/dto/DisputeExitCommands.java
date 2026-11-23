package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.terms.VndAmount;

import java.time.Instant;
import java.util.UUID;

/**
 * D57, D92 dispute exit commands. authTime: the caller's JWT auth_time (step-up), null if the gateway sent none.
 * agreedSellerEntitlementAmount is set for a settlement, null for a concession.
 */
public final class DisputeExitCommands {
    private DisputeExitCommands() {
    }

    public record Initiate(String contractId, String milestoneId, String actorId, UUID idempotencyKey,
                           VndAmount agreedSellerEntitlementAmount, String consentStatementVersion,
                           String presentedContentHash, Instant authTime) {
    }

    public record Verify(String contractId, String milestoneId, String actorId, UUID idempotencyKey, UUID otpId,
                         String otpCode, Instant authTime) {
        @Override
        public String toString() {
            return "Verify[contractId=" + contractId + ", milestoneId=" + milestoneId + ", otpId=" + otpId + "]";
        }
    }

    public record Withdraw(String contractId, String milestoneId, String actorId, UUID idempotencyKey) {
    }
}
