package com.agricontract.contract.application.usecase;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.dto.MilestoneDetailView;
import com.agricontract.contract.application.port.MilestoneDeliverySettings;
import com.agricontract.contract.application.port.MilestoneFlagSettings;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.DisputeExitStep;
import com.agricontract.contract.application.service.MilestoneReadAccess;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.BuyerReceipt;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.model.milestone.SellerWeighing;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.repository.MilestoneEvidenceRepository;
import com.agricontract.contract.domain.repository.SellerWeighDeclarationRepository;
import com.agricontract.contract.domain.repository.SettlementProposalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * getMilestoneDetail (D84, design §3.2.3). BUYER/SELLER of the contract, ARBITRATOR and OPERATOR. One read
 * transaction so the milestone, its declaration and its files are one consistent snapshot.
 */
@Service
@RequiredArgsConstructor
public class GetMilestoneDetailUseCase {
    static final Set<String> STAFF = Set.of("ARBITRATOR", "OPERATOR");

    private final MilestoneReadAccess access;
    private final SellerWeighDeclarationRepository declarations;
    private final MilestoneEvidenceRepository evidence;
    private final MilestoneDeliverySettings settings;
    private final MilestoneFlagSettings flagSettings;
    private final VietnamBusinessDays calendar;
    private final DisputeExitStep exits;
    private final SettlementProposalRepository proposals;
    private final TransactionRunner transactions;

    public MilestoneDetailView execute(String contractId, String milestoneId, String actorId, Set<String> roles) {
        return transactions.inTransaction(() -> {
            MilestoneReadAccess.Readable readable = access.require(contractId, milestoneId, actorId, roles, STAFF);
            Milestone milestone = readable.milestone();
            VndAmount price = milestone.effectiveUnitPrice() != null ? milestone.effectiveUnitPrice()
                    : readable.contract().getCoffeeTerms().snapshot().negotiated().agreedPrice();
            return new MilestoneDetailView(contractId, milestone, price, effectiveDeliveryDeadline(milestone),
                    sellerDeclaration(milestone), buyerReceipt(milestone, readable.contract()),
                    flag(milestone),
                    evidence.findByMilestoneId(milestoneId), disputeExit(readable.contract(), milestone));
        });
    }

    /** D92: null outside the moments where a concession or a settlement can end the dispute. */
    private MilestoneDetailView.DisputeExitBlock disputeExit(Contract contract, Milestone milestone) {
        return exits.peek(contract, milestone).map(w -> new MilestoneDetailView.DisputeExitBlock(w.buyerPosition(),
                w.sellerPosition(), w.concedeAllowed(), w.settlementAllowed(),
                proposals.findOpenByMilestoneId(milestone.milestoneId()).orElse(null))).orElse(null);
    }

    private MilestoneDetailView.SellerDeclaration sellerDeclaration(Milestone milestone) {
        SellerWeighing weighing = milestone.sellerWeighing();
        if (weighing == null) {
            return null;
        }
        List<SellerWeighDeclaration> history = declarations.findByMilestoneId(milestone.milestoneId());
        SellerWeighDeclaration inForce = history.get(history.size() - 1);
        return new MilestoneDetailView.SellerDeclaration(inForce,
                weighing.firstDeclaredAt().plus(settings.weighCorrectionWindow()));
    }

    private MilestoneDetailView.FlagBlock flag(Milestone milestone) {
        if (milestone.flag() == null) {
            return null;
        }
        return new MilestoneDetailView.FlagBlock(milestone.flag(),
                calendar.deadline(milestone.flag().flaggedAt(), flagSettings.sellerResponseWindowDays()));
    }

    private MilestoneDetailView.BuyerReceiptBlock buyerReceipt(Milestone milestone, Contract contract) {
        BuyerReceipt receipt = milestone.buyerReceipt();
        if (receipt == null) {
            return null;
        }
        int days = contract.getCoffeeTerms().snapshot().negotiated().buyerConfirmWindowDays();
        return new MilestoneDetailView.BuyerReceiptBlock(receipt, calendar.deadline(receipt.receivedAt(), days));
    }

    /**
     * expectedDeliveryDate + fundingDelayBusinessDays + graceDays, counted in Vietnam business days (MilestoneResponse,
     * design §6b).
     */
    LocalDate effectiveDeliveryDeadline(Milestone milestone) {
        LocalDate date = milestone.expectedDeliveryDate();
        int remaining = milestone.fundingDelayBusinessDays() + milestone.graceDays();
        while (remaining > 0) {
            date = date.plusDays(1);
            if (calendar.isBusinessDay(date)) {
                remaining--;
            }
        }
        return date;
    }
}
