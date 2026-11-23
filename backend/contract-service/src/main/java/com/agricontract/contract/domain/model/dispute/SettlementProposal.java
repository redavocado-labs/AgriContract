package com.agricontract.contract.domain.model.dispute;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An agreed-settlement proposal for a disputed milestone (D57, D92): the seller entitlement one party offers. At most
 * one OPEN per milestone. It ends ACCEPTED (the other party confirmed the same amount), WITHDRAWN (by the proposer),
 * REFUSED (by the other party) or LAPSED (the dispute ended another way, or a COMPLETE report decided it). A proposal
 * never stops the inspection.
 */
public record SettlementProposal(String proposalId, String contractId, String milestoneId,
                                 VndAmount agreedSellerEntitlementAmount, SignerRole proposedByRole,
                                 String proposedByActorId, Instant proposedAt, SettlementProposalStatus status,
                                 Instant endedAt, String endedByActorId) {
    public SettlementProposal {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(milestoneId, "milestoneId");
        Objects.requireNonNull(agreedSellerEntitlementAmount, "agreedSellerEntitlementAmount");
        Objects.requireNonNull(proposedByRole, "proposedByRole");
        Objects.requireNonNull(proposedByActorId, "proposedByActorId");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(status, "status");
        if ((status == SettlementProposalStatus.OPEN) != (endedAt == null)) {
            throw new IllegalArgumentException("a proposal has endedAt exactly when it is no longer OPEN");
        }
    }

    public static SettlementProposal open(String contractId, String milestoneId, VndAmount amount, SignerRole role,
                                          String actorId, Instant now) {
        return new SettlementProposal(UUID.randomUUID().toString(), contractId, milestoneId, amount, role, actorId,
                now, SettlementProposalStatus.OPEN, null, null);
    }

    /**
     * The caller (role) confirms this open proposal with {@code amount}: 409 SETTLEMENT_PROPOSAL_CONFLICT when the
     * caller is the proposer or the amount differs.
     */
    public void requireAcceptableBy(SignerRole role, VndAmount amount) {
        if (role == proposedByRole) {
            throw conflict("You already proposed " + agreedSellerEntitlementAmount.wire()
                    + "; the other party has to accept it, or withdraw it first.");
        }
        if (amount.compareTo(agreedSellerEntitlementAmount) != 0) {
            throw conflict("The open proposal is " + agreedSellerEntitlementAmount.wire()
                    + "; accept exactly that amount, or refuse it first.");
        }
    }

    /** The end a DELETE by this role gives: WITHDRAWN for the proposer, REFUSED for the other party. */
    public SettlementProposalStatus endingBy(SignerRole role) {
        return role == proposedByRole ? SettlementProposalStatus.WITHDRAWN : SettlementProposalStatus.REFUSED;
    }

    public static ContractConflictException conflict(String message) {
        return new ContractConflictException(ContractConflictException.SETTLEMENT_PROPOSAL_CONFLICT, message);
    }
}
