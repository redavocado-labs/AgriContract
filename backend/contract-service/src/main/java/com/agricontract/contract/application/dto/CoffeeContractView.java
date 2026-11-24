package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.vo.ContractStatus;

import java.math.BigDecimal;

/** Phase 2 contract read model. termsJson is the stored canonical JSON, returned verbatim. */
public record CoffeeContractView(
        String contractId,
        String listingId,
        BigDecimal reservedQuantity,
        String buyerId,
        String sellerId,
        ContractStatus status,
        String termsJson,
        String signedContentHash,
        ActivationRecoveryView activationRecovery,
        java.util.List<MilestoneSummary> milestones,
        TerminalDecisionView terminalDecision) {

    public CoffeeContractView {
        milestones = milestones == null ? java.util.List.of() : java.util.List.copyOf(milestones);
    }

    /** Without activation recovery, milestones or terminal decision: an offer, or a contract read before them. */
    public CoffeeContractView(String contractId, String listingId, BigDecimal reservedQuantity, String buyerId,
                              String sellerId, ContractStatus status, String termsJson, String signedContentHash) {
        this(contractId, listingId, reservedQuantity, buyerId, sellerId, status, termsJson, signedContentHash, null,
                null, null);
    }

    public CoffeeContractView withActivationRecovery(ActivationRecoveryView recovery) {
        return new CoffeeContractView(contractId, listingId, reservedQuantity, buyerId, sellerId, status, termsJson,
                signedContentHash, recovery, milestones, terminalDecision);
    }

    /** D99: the contract's milestones in order and its final money decision, if any. */
    public CoffeeContractView withExecution(java.util.List<MilestoneSummary> summaries, TerminalDecisionView decision) {
        return new CoffeeContractView(contractId, listingId, reservedQuantity, buyerId, sellerId, status, termsJson,
                signedContentHash, activationRecovery, summaries, decision);
    }

    public static CoffeeContractView from(Contract contract) {
        var terms = contract.getCoffeeTerms();
        return new CoffeeContractView(contract.getContractId().value(), contract.getListingId(),
                contract.getReservedQuantity(), contract.getBuyerId(), contract.getSellerId(), contract.getStatus(),
                terms.canonicalJson(), terms.signedContentHash());
    }
}
