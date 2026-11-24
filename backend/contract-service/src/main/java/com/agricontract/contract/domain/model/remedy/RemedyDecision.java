package com.agricontract.contract.domain.model.remedy;

import com.agricontract.contract.domain.model.breach.BreachReasonCode;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.terms.VndAmount;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The money consequence of an AttributionDecision on a milestone dispute (#137). Immutable once built: the only
 * change is PENDING_CONTEST becoming FINALIZED, or SUPERSEDED when a contest's second Level 1.5 report (or an agreed
 * exit, D92) replaced it. A no-fault decision is FINALIZED at once, and so is the decision of that second report and
 * any MUTUAL decision; any other decision with fault waits for the contest window (#241). penaltyAmount and
 * penaltyDebtAmount are evidence of the contractual penalty: the part the seller deposit could not cover is a debt that
 * is not collected, and has no leg.
 *
 * @param breachCaseId          null only for a decision of the system: the normal completion of the contract (#238,
 *                              D94), later a Basket A attribution (#240)
 * @param milestoneId           null only for a contract-level decision (normal completion: the deposits), which then
 *                              has no breach case either
 * @param settledMilestoneCount milestones of the contract already SETTLED when the decision was made (D55)
 * @param inspectionFeeParty    who bears the Level 1.5 inspection fee (D92), null for no fee
 */
public record RemedyDecision(String remedyDecisionId, String contractId, String breachCaseId, String milestoneId,
                             RemedyStatus status, SignerRole finalBreachingRole, BreachReasonCode breachReasonCode,
                             QualityDisposition qualityDisposition, DecisionSource decisionSource,
                             VndAmount penaltyAmount, VndAmount penaltyDebtAmount, int settledMilestoneCount,
                             List<RemedyLeg> legs, Instant createdAt, Instant finalizedAt,
                             InspectionFeeParty inspectionFeeParty) {
    public RemedyDecision {
        Objects.requireNonNull(remedyDecisionId, "remedyDecisionId");
        Objects.requireNonNull(contractId, "contractId");
        if (milestoneId == null && breachCaseId != null) {
            throw new IllegalArgumentException("a decision on a breach case is a milestone decision");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decisionSource, "decisionSource");
        Objects.requireNonNull(penaltyAmount, "penaltyAmount");
        Objects.requireNonNull(penaltyDebtAmount, "penaltyDebtAmount");
        Objects.requireNonNull(createdAt, "createdAt");
        legs = List.copyOf(legs);
        if ((status == RemedyStatus.FINALIZED) != (finalizedAt != null)) {
            throw new IllegalArgumentException("a remedy decision is FINALIZED exactly when it has finalizedAt");
        }
        if (settledMilestoneCount < 0) {
            throw new IllegalArgumentException("settledMilestoneCount must not be negative");
        }
    }

    /** A decision with no inspection fee party (the shape before D92). */
    public RemedyDecision(String remedyDecisionId, String contractId, String breachCaseId, String milestoneId,
                          RemedyStatus status, SignerRole finalBreachingRole, BreachReasonCode breachReasonCode,
                          QualityDisposition qualityDisposition, DecisionSource decisionSource,
                          VndAmount penaltyAmount, VndAmount penaltyDebtAmount, int settledMilestoneCount,
                          List<RemedyLeg> legs, Instant createdAt, Instant finalizedAt) {
        this(remedyDecisionId, contractId, breachCaseId, milestoneId, status, finalBreachingRole, breachReasonCode,
                qualityDisposition, decisionSource, penaltyAmount, penaltyDebtAmount, settledMilestoneCount, legs,
                createdAt, finalizedAt, null);
    }

    /** A decision on the whole contract (the deposits), not on one milestone: affectedMilestoneIds is empty. */
    public boolean contractLevel() {
        return milestoneId == null;
    }

    /**
     * attributionDecisionId of remedy.finalized (D94): the breach case id when there is a case (the attribution lives
     * on the case, #135), else the decision's own id; there is no separate AttributionDecision record.
     */
    public String attributionDecisionId() {
        return breachCaseId != null ? breachCaseId : remedyDecisionId;
    }

    /** penaltyEligible of remedy.finalized: only a decision with fault carries a penalty. */
    public boolean penaltyEligible() {
        return finalBreachingRole != null;
    }

    /** reputationEligible of remedy.finalized: only a decision with fault locks reputation. */
    public boolean reputationEligible() {
        return finalBreachingRole != null;
    }

    /** PENDING_CONTEST becomes FINALIZED, once; the caller checks the status first. */
    public RemedyDecision finalizedAt(Instant at) {
        Objects.requireNonNull(at, "at");
        if (status != RemedyStatus.PENDING_CONTEST) {
            throw new IllegalStateException("Remedy decision " + remedyDecisionId + " is " + status);
        }
        return new RemedyDecision(remedyDecisionId, contractId, breachCaseId, milestoneId, RemedyStatus.FINALIZED,
                finalBreachingRole, breachReasonCode, qualityDisposition, decisionSource, penaltyAmount,
                penaltyDebtAmount, settledMilestoneCount, legs, createdAt, at, inspectionFeeParty);
    }

    /** What the seller is paid from the milestone, 0.00 when there is no PAYMENT_SETTLEMENT leg. */
    public VndAmount sellerPayment() {
        return legAmount(RemedyType.PAYMENT_SETTLEMENT);
    }

    /** What goes back to the buyer from the milestone, 0.00 when there is no PAYMENT_REFUND leg. */
    public VndAmount buyerRefund() {
        return legAmount(RemedyType.PAYMENT_REFUND);
    }

    /** What the buyer takes from the seller deposit, 0.00 when there is no forfeiture leg. */
    public VndAmount sellerDepositForfeited() {
        return legAmount(RemedyType.DEPOSIT_FORFEITURE);
    }

    private VndAmount legAmount(RemedyType type) {
        return legs.stream().filter(l -> l.remedyType() == type).map(RemedyLeg::amount).findFirst()
                .orElse(new VndAmount(BigDecimal.ZERO));
    }
}
