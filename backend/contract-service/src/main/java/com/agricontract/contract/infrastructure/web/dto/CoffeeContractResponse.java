package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.application.dto.ActivationRecoveryView;
import com.agricontract.contract.application.dto.CoffeeContractView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.util.List;

/**
 * ContractResponse of golden-flow-api.yaml, without the Phase 1 response wrapper (D32). terms is written as the
 * stored canonical JSON so the client sees exactly the bytes behind signedContentHash. Milestones in order (D99).
 */
public record CoffeeContractResponse(
        String contractId,
        String listingId,
        BigDecimal reservedQuantity,
        String buyerId,
        String sellerId,
        String status,
        @JsonRawValue String terms,
        String signedContentHash,
        List<MilestoneDetailResponse.MilestoneResponse> milestones,
        @JsonInclude(JsonInclude.Include.NON_NULL) ActivationRecovery activationRecovery,
        @JsonInclude(JsonInclude.Include.NON_NULL) TerminalDecision terminalDecision) {

    /** D99: present from the final money decision on; moneyCleared once the contract reached targetStatus. */
    public record TerminalDecision(String targetStatus, boolean moneyCleared, RemedyDecisionResponse remedyDecision) {
    }

    /** Present only after escrow.deposit_lock_failed (golden-flow-api ContractResponse.activationRecovery, D42). */
    public record ActivationRecovery(String depositLockFailedAt, String oneSidedCancelAllowedFrom,
                                     List<String> cancelRequestedBy) {
    }

    public static CoffeeContractResponse from(CoffeeContractView view) {
        ActivationRecoveryView recovery = view.activationRecovery();
        return new CoffeeContractResponse(view.contractId(), view.listingId(), view.reservedQuantity(),
                view.buyerId(), view.sellerId(), view.status().name(), view.termsJson(), view.signedContentHash(),
                view.milestones().stream().map(MilestoneDetailResponse::summary).toList(),
                recovery == null ? null : new ActivationRecovery(
                        recovery.depositLockFailedAt().toString(), recovery.oneSidedCancelAllowedFrom().toString(),
                        recovery.cancelRequestedBy()),
                view.terminalDecision() == null ? null : new TerminalDecision(
                        view.terminalDecision().targetStatus().name(), view.terminalDecision().moneyCleared(),
                        RemedyDecisionResponse.from(view.terminalDecision().remedyDecision())));
    }
}
