package com.agricontract.contract.domain.model.dispute;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseComputedResolution;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.domain.model.breach.ContestStatus;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.inspection.InspectionReportPurpose;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.quality.MeasurementStatus;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a milestone dispute can end by a concession or an agreed settlement now (D57, D92), and the two positions
 * the parties can accept. Pure: the caller loads the milestone, its case, the case's latest RemedyDecision and the
 * statuses of the milestone's stored reports under the row locks.
 *
 * <p>The moments (else none):
 * <ul>
 *   <li>AWAITING_SELLER_RESPONSE: concession only (the seller accepting the flag, the buyer withdrawing it);</li>
 *   <li>CONTESTED, the case not RESOLVED and without a computed resolution, no COMPLETE report of the flag round
 *       (INITIAL or RERUN): concession or settlement;</li>
 *   <li>a decision under contest (case RESOLVED, contestStatus CONTESTED, its RemedyDecision PENDING_CONTEST), no
 *       COMPLETE CONTEST report: concession or settlement.</li>
 * </ul>
 * Never for a case the system opened (mandatory inspection). Positions, as seller entitlement in whole dong (HALF_UP
 * once, capped at the locked batch L, price P): seller = min(L, sellerDeclaredKg x P); buyer = min(L, buyerReceivedKg x
 * P) for a QUANTITY flag, 0 for a QUALITY flag (the lot rejected), and the pending decision's seller payment for a
 * decision under contest.
 *
 * @param pendingRoundInconclusive the first report of the pending round came back INCONCLUSIVE (the inspector did the
 *                                 work): it decides the inspection fee of the exit
 */
public record DisputeExitWindow(DisputeExitStage stage, FlagIssueType issueType, VndAmount lockedAmount,
                                VndAmount buyerPosition, VndAmount sellerPosition,
                                BigDecimal sellerDeclaredKg, BigDecimal buyerReceivedKg, VndAmount unitPrice,
                                boolean pendingRoundInconclusive) {
    public DisputeExitWindow {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(issueType, "issueType");
        Objects.requireNonNull(lockedAmount, "lockedAmount");
        Objects.requireNonNull(buyerPosition, "buyerPosition");
        Objects.requireNonNull(sellerPosition, "sellerPosition");
        Objects.requireNonNull(unitPrice, "unitPrice");
    }

    /**
     * The exit window of the milestone now, or empty when no exit is allowed.
     *
     * @param breachCase the milestone's case, null before the contest step
     * @param latest     the case's latest RemedyDecision, null when it has none
     * @param reports    the measurement status of each stored report of the milestone, by purpose
     */
    public static Optional<DisputeExitWindow> assess(Milestone milestone, BreachCase breachCase, RemedyDecision latest,
                                                     Map<InspectionReportPurpose, MeasurementStatus> reports) {
        Objects.requireNonNull(milestone, "milestone");
        Objects.requireNonNull(reports, "reports");
        if (milestone.flag() == null || milestone.sellerWeighing() == null || milestone.buyerReceipt() == null
                || milestone.batchAmount() == null || milestone.effectiveUnitPrice() == null) {
            return Optional.empty();
        }
        FlagIssueType issue = milestone.flag().issueType();
        VndAmount locked = milestone.batchAmount();
        VndAmount price = milestone.effectiveUnitPrice();
        BigDecimal sellerKg = milestone.sellerWeighing().declaredWeight();
        BigDecimal buyerKg = milestone.buyerReceipt().receivedWeight();
        VndAmount sellerPosition = capped(sellerKg, price, locked);
        VndAmount flagBuyerPosition = issue == FlagIssueType.QUANTITY ? capped(buyerKg, price, locked) : zero();
        boolean initialInconclusive = reports.get(InspectionReportPurpose.INITIAL) == MeasurementStatus.INCONCLUSIVE;

        if (milestone.status() == MilestoneStatus.AWAITING_SELLER_RESPONSE) {
            if (breachCase != null) {
                return Optional.empty();
            }
            return Optional.of(new DisputeExitWindow(DisputeExitStage.AWAITING_SELLER_RESPONSE, issue, locked,
                    flagBuyerPosition, sellerPosition, sellerKg, buyerKg, price, initialInconclusive));
        }
        if (milestone.status() != MilestoneStatus.CONTESTED || breachCase == null || breachCase.openedBySystem()
                || !breachCase.breachCaseId().equals(milestone.flag().breachCaseId())) {
            return Optional.empty();
        }
        if (breachCase.status() != BreachCaseStatus.RESOLVED) {
            boolean decided = breachCase.computedResolution() != null
                    || reports.get(InspectionReportPurpose.INITIAL) == MeasurementStatus.COMPLETE
                    || reports.get(InspectionReportPurpose.RERUN) == MeasurementStatus.COMPLETE;
            return decided ? Optional.empty() : Optional.of(new DisputeExitWindow(DisputeExitStage.CONTESTED_FLAG,
                    issue, locked, flagBuyerPosition, sellerPosition, sellerKg, buyerKg, price, initialInconclusive));
        }
        MeasurementStatus contestReport = reports.get(InspectionReportPurpose.CONTEST);
        if (breachCase.contestStatus() != ContestStatus.CONTESTED || latest == null
                || latest.status() != RemedyStatus.PENDING_CONTEST || contestReport == MeasurementStatus.COMPLETE) {
            return Optional.empty();
        }
        return Optional.of(new DisputeExitWindow(DisputeExitStage.CONTESTED_DECISION, issue, locked,
                latest.sellerPayment(), sellerPosition, sellerKg, buyerKg, price,
                contestReport == MeasurementStatus.INCONCLUSIVE));
    }

    /** 409 DISPUTE_EXIT_NOT_ALLOWED when the milestone has no exit window now. */
    public static DisputeExitWindow require(Optional<DisputeExitWindow> window) {
        return window.orElseThrow(() -> new ContractConflictException(
                ContractConflictException.DISPUTE_EXIT_NOT_ALLOWED,
                "This milestone dispute cannot be ended by a concession or a settlement now."));
    }

    /** A concession is allowed at every moment of the window. */
    public boolean concedeAllowed() {
        return true;
    }

    /** A settlement needs a contested milestone: never while the flag waits for the seller's response. */
    public boolean settlementAllowed() {
        return stage != DisputeExitStage.AWAITING_SELLER_RESPONSE;
    }

    /** 409 DISPUTE_EXIT_NOT_ALLOWED when a settlement is not allowed at this moment. */
    public void requireSettlementAllowed() {
        if (!settlementAllowed()) {
            throw new ContractConflictException(ContractConflictException.DISPUTE_EXIT_NOT_ALLOWED,
                    "A settlement needs a contested milestone; the flag still waits for the seller's response.");
        }
    }

    /** The lower and upper end of a settlement, both included. */
    public VndAmount lowerBound() {
        return buyerPosition.compareTo(sellerPosition) <= 0 ? buyerPosition : sellerPosition;
    }

    public VndAmount upperBound() {
        return buyerPosition.compareTo(sellerPosition) <= 0 ? sellerPosition : buyerPosition;
    }

    /** 400 (IllegalArgumentException) when the agreed seller entitlement lies outside the two positions. */
    public void requireWithinRange(VndAmount agreed) {
        Objects.requireNonNull(agreed, "agreed");
        if (agreed.compareTo(lowerBound()) < 0 || agreed.compareTo(upperBound()) > 0) {
            throw new IllegalArgumentException("agreedSellerEntitlementAmount " + agreed.wire()
                    + " must lie between " + lowerBound().wire() + " and " + upperBound().wire());
        }
    }

    /**
     * The position the conceding party accepts. The seller accepting the buyer's position: a QUANTITY flag settles on
     * the buyer's figure with no fault (D83); a QUALITY flag or a decision under contest is NON_CONFORMING with the
     * seller at fault. The buyer accepting the seller's position: the seller's figure, no fault.
     */
    public BreachCaseComputedResolution concession(SignerRole conceder) {
        Objects.requireNonNull(conceder, "conceder");
        if (conceder == SignerRole.BUYER) {
            return BreachCaseComputedResolution.agreed(issueType, sellerDeclaredKg, null, null, sellerPosition,
                    rest(sellerPosition), SignerRole.SELLER);
        }
        if (stage == DisputeExitStage.CONTESTED_DECISION || issueType == FlagIssueType.QUALITY) {
            return BreachCaseComputedResolution.agreed(issueType, BigDecimal.ZERO, QualityDisposition.NON_CONFORMING,
                    SignerRole.SELLER, zero(), lockedAmount, SignerRole.BUYER);
        }
        return BreachCaseComputedResolution.agreed(issueType, buyerReceivedKg, null, null, buyerPosition,
                rest(buyerPosition), SignerRole.BUYER);
    }

    /**
     * The agreed settlement: the seller gets {@code agreed}, the buyer the rest of the locked batch, nobody at fault.
     * The billable quantity is agreed / unit price, to the gram (HALF_UP). 400 outside the positions.
     */
    public BreachCaseComputedResolution settlement(VndAmount agreed) {
        requireWithinRange(agreed);
        BigDecimal kg = agreed.value().divide(unitPrice.value(), 3, RoundingMode.HALF_UP);
        return BreachCaseComputedResolution.agreed(issueType, kg, null, null, agreed, rest(agreed), null);
    }

    private VndAmount rest(VndAmount sellerAmount) {
        return new VndAmount(lockedAmount.value().subtract(sellerAmount.value()));
    }

    private static VndAmount capped(BigDecimal kg, VndAmount price, VndAmount locked) {
        VndAmount owed = VndAmount.ofDong(kg.multiply(price.value()));
        return owed.compareTo(locked) > 0 ? locked : owed;
    }

    private static VndAmount zero() {
        return new VndAmount(BigDecimal.ZERO);
    }
}
