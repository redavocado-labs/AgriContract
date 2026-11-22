package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * getMilestoneDetail (D84, design §3.2.3): the milestone and what has happened on it, every deadline computed here.
 * The buyer receipt, flag, settlement and breach case blocks are added by the issues that produce them (#122 onward).
 *
 * @param effectiveUnitPrice the milestone's own price once funding was requested, the signed price before
 * @param sellerDeclaration  the declaration in force, null before the seller weighed
 */
public record MilestoneDetailView(String contractId, Milestone milestone, VndAmount effectiveUnitPrice,
                                  LocalDate effectiveDeliveryDeadline, SellerDeclaration sellerDeclaration,
                                  List<MilestoneEvidenceFile> evidence) {

    /** correctionWindowClosesAt = first declaration + the window (D75); a correction never moves it. */
    public record SellerDeclaration(SellerWeighDeclaration declaration, Instant correctionWindowClosesAt) {
    }
}
