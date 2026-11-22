package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.milestone.BuyerReceipt;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneEvidenceFile;
import com.agricontract.contract.domain.model.milestone.MilestoneFlag;
import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * getMilestoneDetail (D84, design §3.2.3): the milestone and what has happened on it, every deadline computed here.
 * The settlement and the breach case id are read from the milestone itself (null until SETTLED / contested).
 *
 * @param effectiveUnitPrice the milestone's own price once funding was requested, the signed price before
 * @param sellerDeclaration  the declaration in force, null before the seller weighed
 * @param buyerReceipt       null until the buyer recorded receipt
 */
public record MilestoneDetailView(String contractId, Milestone milestone, VndAmount effectiveUnitPrice,
                                  LocalDate effectiveDeliveryDeadline, SellerDeclaration sellerDeclaration,
                                  BuyerReceiptBlock buyerReceipt, FlagBlock flag,
                                  List<MilestoneEvidenceFile> evidence) {

    /** confirmWindowClosesAt = receivedAt + buyerConfirmWindowDays business days (D76). */
    public record BuyerReceiptBlock(BuyerReceipt receipt, Instant confirmWindowClosesAt) {
    }

    /** sellerResponseWindowClosesAt = flaggedAt + sellerResponseWindowDays business days (D80, D82); null block until flagged. */
    public record FlagBlock(MilestoneFlag flag, Instant sellerResponseWindowClosesAt) {
    }

    /** correctionWindowClosesAt = first declaration + the window (D75); a correction never moves it. */
    public record SellerDeclaration(SellerWeighDeclaration declaration, Instant correctionWindowClosesAt) {
    }
}
