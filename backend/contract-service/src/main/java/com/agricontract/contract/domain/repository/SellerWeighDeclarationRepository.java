package com.agricontract.contract.domain.repository;

import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;

import java.util.List;

/** Every weigh declaration of the seller (D75). Append-only: there is deliberately no update or delete. */
public interface SellerWeighDeclarationRepository {
    void append(SellerWeighDeclaration declaration);

    /** Oldest first. */
    List<SellerWeighDeclaration> findByMilestoneId(String milestoneId);
}
