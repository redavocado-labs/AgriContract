package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.dispute.DisputeExitType;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.util.Optional;
import java.util.UUID;

/** dispute_exit_otp (V26): what a CONCEDE_DISPUTE / SETTLE_DISPUTE code confirms beyond the contract. */
public interface DisputeExitChallengeRepository {

    /**
     * @param milestoneId the milestone whose dispute the code ends
     * @param amount      the agreed seller entitlement of a settlement, null for a concession
     */
    record Binding(UUID otpId, String milestoneId, DisputeExitType type, VndAmount amount) {
    }

    void insert(Binding binding);

    Optional<Binding> findByOtpId(UUID otpId);
}
