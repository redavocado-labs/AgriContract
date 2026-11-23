package com.agricontract.contract.domain.model.remedy;

import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseComputedResolution;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.domain.model.breach.BreachReasonCode;
import com.agricontract.contract.domain.model.breach.ContestStatus;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.terms.ContractTermsSnapshot;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds the RemedyDecision of a resolved milestone dispute from the case's computed resolution (#137, D81). A
 * decision with fault waits as PENDING_CONTEST (#241), unless the case is already CONTESTED (D91). Pure:
 * no clock, no repository; ids come from the supplier.
 *
 * <p>CONFORMING, PARTIALLY_CONFORMING and QUANTITY: PAYMENT_SETTLEMENT to the seller and PAYMENT_REFUND to the buyer,
 * as computed. NON_CONFORMING: the whole milestone goes back to the buyer, and the contractual penalty
 * sellerPenaltyRate x effectiveUnitPrice x committedQuantity (never totalContractValue) is taken from the seller
 * deposit as a DEPOSIT_FORFEITURE leg, capped by what is left of that deposit; the rest is penaltyDebtAmount, evidence
 * only. There is never a DAMAGES_COMPENSATION leg, so nothing is recovered twice.
 */
public final class RemedyCalculator {
    private final Supplier<String> ids;

    public RemedyCalculator(Supplier<String> ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * @param remainingSellerDeposit the seller deposit minus the SELLER_DEPOSIT legs of the contract's earlier remedy
     *                               decisions; only read for NON_CONFORMING
     * @param settledMilestoneCount  milestones of the contract SETTLED before this one
     */
    public RemedyDecision calculate(BreachCase breachCase, Milestone milestone, ContractTermsSnapshot terms,
                                    VndAmount remainingSellerDeposit, int settledMilestoneCount, Instant now) {
        BreachCaseComputedResolution computed = breachCase.computedResolution();
        if (computed == null || computed.isAgreed()) {
            throw new IllegalStateException("Case " + breachCase.breachCaseId()
                    + " has no computed resolution from a Level 1.5 report");
        }
        // D92: the report-based decision puts the inspection fee on the party the report did not side with.
        InspectionFeeParty fee = InspectionFeeParty.of(computed.prevailingRole() == SignerRole.BUYER
                ? SignerRole.SELLER : SignerRole.BUYER);
        return build(breachCase, milestone, terms, remainingSellerDeposit, settledMilestoneCount, fee, now);
    }

    /**
     * D57, D92: the decision of a dispute the parties ended themselves (decisionSource MUTUAL, agreed computed
     * resolution). Always FINALIZED at once, with fault too: a concession or a settlement has no contest window.
     *
     * @param inspectionFeeParty who bears the inspection fee, null for none (the caller applies the D92 rule)
     */
    public RemedyDecision calculateAgreed(BreachCase breachCase, Milestone milestone, ContractTermsSnapshot terms,
                                          VndAmount remainingSellerDeposit, int settledMilestoneCount,
                                          InspectionFeeParty inspectionFeeParty, Instant now) {
        BreachCaseComputedResolution computed = breachCase.computedResolution();
        if (computed == null || !computed.isAgreed() || breachCase.decisionSource() != DecisionSource.MUTUAL) {
            throw new IllegalStateException("Case " + breachCase.breachCaseId() + " was not ended by agreement");
        }
        return build(breachCase, milestone, terms, remainingSellerDeposit, settledMilestoneCount, inspectionFeeParty,
                now);
    }

    private RemedyDecision build(BreachCase breachCase, Milestone milestone, ContractTermsSnapshot terms,
                                 VndAmount remainingSellerDeposit, int settledMilestoneCount,
                                 InspectionFeeParty inspectionFeeParty, Instant now) {
        Objects.requireNonNull(now, "now");
        BreachCaseComputedResolution computed = breachCase.computedResolution();
        if (breachCase.status() != BreachCaseStatus.RESOLVED || computed == null || breachCase.milestoneId() == null
                || !breachCase.milestoneId().equals(milestone.milestoneId())) {
            throw new IllegalStateException("Case " + breachCase.breachCaseId()
                    + " is not a resolved milestone case with a computed resolution");
        }
        if (milestone.batchAmount() == null || milestone.effectiveUnitPrice() == null) {
            throw new IllegalStateException("Milestone " + milestone.milestoneId() + " has no locked batch");
        }
        if (breachCase.finalBreachingRole() != computed.finalBreachingRole()) {
            throw new IllegalStateException("The decision of case " + breachCase.breachCaseId()
                    + " differs from its computed resolution");
        }
        BigDecimal locked = milestone.batchAmount().value();
        boolean nonConforming = computed.qualityDisposition() == QualityDisposition.NON_CONFORMING;
        if (nonConforming != (computed.finalBreachingRole() == SignerRole.SELLER)) {
            throw new IllegalStateException("Only NON_CONFORMING blames the seller on the dispute path");
        }
        List<RemedyLeg> legs = new ArrayList<>();
        VndAmount penalty = zero();
        VndAmount debt = zero();
        if (nonConforming) {
            legs.add(leg(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT, SignerRole.BUYER,
                    milestone.batchAmount()));
            penalty = penalty(milestone, terms);
            VndAmount forfeit = min(penalty, remainingSellerDeposit);
            debt = new VndAmount(penalty.value().subtract(forfeit.value()));
            if (forfeit.value().signum() > 0) {
                legs.add(leg(RemedyType.DEPOSIT_FORFEITURE, RemedyFundType.SELLER_DEPOSIT, SignerRole.BUYER, forfeit));
            }
        } else {
            if (computed.sellerEntitlementAmount().value().add(computed.buyerRefundAmount().value())
                    .compareTo(locked) != 0) {
                throw new IllegalStateException("The computed resolution of case " + breachCase.breachCaseId()
                        + " does not add up to the locked batch");
            }
            if (computed.sellerEntitlementAmount().value().signum() > 0) {
                legs.add(leg(RemedyType.PAYMENT_SETTLEMENT, RemedyFundType.MILESTONE_PAYMENT, SignerRole.SELLER,
                        computed.sellerEntitlementAmount()));
            }
            if (computed.buyerRefundAmount().value().signum() > 0) {
                legs.add(leg(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT, SignerRole.BUYER,
                        computed.buyerRefundAmount()));
            }
        }
        BigDecimal paid = legs.stream().filter(l -> l.fundType() == RemedyFundType.MILESTONE_PAYMENT)
                .map(l -> l.amount().value()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (paid.compareTo(locked) != 0) {
            throw new IllegalStateException("MILESTONE_PAYMENT legs " + paid + " differ from the locked batch " + locked);
        }
        boolean fault = computed.finalBreachingRole() != null;
        // D91: the decision of a contest's second Level 1.5 report is final, with fault too: no new window. D92: so is
        // a decision the parties reached themselves (MUTUAL).
        boolean pending = fault && breachCase.contestStatus() != ContestStatus.CONTESTED
                && breachCase.decisionSource() != DecisionSource.MUTUAL;
        return new RemedyDecision(ids.get(), breachCase.contractId(), breachCase.breachCaseId(),
                milestone.milestoneId(), pending ? RemedyStatus.PENDING_CONTEST : RemedyStatus.FINALIZED,
                computed.finalBreachingRole(), fault ? BreachReasonCode.QUALITY_BELOW_COMMITTED : null,
                computed.qualityDisposition(), breachCase.decisionSource(), penalty, debt, settledMilestoneCount, legs,
                now, pending ? null : now, inspectionFeeParty);
    }

    /** sellerPenaltyRate x effectiveUnitPrice x committedQuantity of the milestone, whole dong, HALF_UP once. */
    static VndAmount penalty(Milestone milestone, ContractTermsSnapshot terms) {
        BigDecimal rate = terms.negotiated().sellerPenaltyRate();
        BigDecimal cap = terms.negotiated().legalProfile().governingLaw().maxContractualPenaltyRate();
        if (rate.compareTo(cap) > 0) {
            throw new IllegalStateException("sellerPenaltyRate " + rate + " is above the legal cap " + cap);
        }
        return VndAmount.ofDong(rate.multiply(milestone.effectiveUnitPrice().value())
                .multiply(milestone.committedQuantity()));
    }

    private RemedyLeg leg(RemedyType type, RemedyFundType fund, SignerRole payee, VndAmount amount) {
        return new RemedyLeg(ids.get(), type, fund, payee, amount);
    }

    private static VndAmount min(VndAmount a, VndAmount b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static VndAmount zero() {
        return new VndAmount(BigDecimal.ZERO);
    }
}
