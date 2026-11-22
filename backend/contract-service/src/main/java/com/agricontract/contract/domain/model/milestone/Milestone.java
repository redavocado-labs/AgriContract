package com.agricontract.contract.domain.model.milestone;

import com.agricontract.contract.domain.model.terms.MilestoneTerm;
import com.agricontract.contract.domain.model.terms.VndAmount;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.consent.SignerRole;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One delivery batch of an ACTIVE contract (milestone-escrow design §2.2): its own aggregate, loaded and saved on its
 * own, never as a child of Contract. Created for every MilestoneTerm when the contract becomes ACTIVE.
 *
 * <p>Funding (D63, D64): on {@code fundingScheduledDate} contract-service fixes {@code batchAmount} and asks escrow to
 * lock it ({@link #requestFunding}). Each milestone follows its own date and never waits for the previous one.
 * The milestone stays CREATED until escrow reports the lock ({@link #recordFunded}); only then does the seller's
 * delivery obligation start, and a late lock moves the deadline by {@code fundingDelayBusinessDays}.
 */
public final class Milestone {
    public static final int MAX_FAILURE_REASON = 500;

    private final String milestoneId;
    private final String contractId;
    private final int milestoneIndex;
    private final BigDecimal committedQuantity;
    private final LocalDate expectedDeliveryDate;
    private final int graceDays;
    private final LocalDate fundingScheduledDate;
    private MilestoneStatus status;
    private FundingStatus fundingStatus;
    private VndAmount effectiveUnitPrice;
    private VndAmount batchAmount;
    private Instant fundingRequestedAt;
    private Instant fundingLockedAt;
    private int fundingDelayBusinessDays;
    private String lastFundingFailureReason;
    private SellerWeighing sellerWeighing;
    private BuyerReceipt buyerReceipt;
    private MilestoneSettlement settlement;
    private MilestoneFlag flag;

    private Milestone(String milestoneId, String contractId, int milestoneIndex, BigDecimal committedQuantity,
                      LocalDate expectedDeliveryDate, int graceDays, LocalDate fundingScheduledDate,
                      MilestoneStatus status, FundingStatus fundingStatus, VndAmount effectiveUnitPrice,
                      VndAmount batchAmount, Instant fundingRequestedAt, Instant fundingLockedAt,
                      int fundingDelayBusinessDays, String lastFundingFailureReason,
                      SellerWeighing sellerWeighing, BuyerReceipt buyerReceipt,
                      MilestoneSettlement settlement, MilestoneFlag flag) {
        this.flag = flag;
        this.milestoneId = milestoneId;
        this.contractId = contractId;
        this.milestoneIndex = milestoneIndex;
        this.committedQuantity = committedQuantity;
        this.expectedDeliveryDate = expectedDeliveryDate;
        this.graceDays = graceDays;
        this.fundingScheduledDate = fundingScheduledDate;
        this.status = status;
        this.fundingStatus = fundingStatus;
        this.effectiveUnitPrice = effectiveUnitPrice;
        this.batchAmount = batchAmount;
        this.fundingRequestedAt = fundingRequestedAt;
        this.fundingLockedAt = fundingLockedAt;
        this.fundingDelayBusinessDays = fundingDelayBusinessDays;
        this.lastFundingFailureReason = lastFundingFailureReason;
        this.sellerWeighing = sellerWeighing;
        this.buyerReceipt = buyerReceipt;
        this.settlement = settlement;
    }

    /**
     * A new milestone of a contract that just became ACTIVE, from its signed term. fundingScheduledDate is computed by
     * the caller on the Vietnam calendar: expectedDeliveryDate minus fundingLeadBusinessDays business days.
     */
    public static Milestone plan(String milestoneId, String contractId, MilestoneTerm term,
                                 LocalDate fundingScheduledDate) {
        Objects.requireNonNull(milestoneId, "milestoneId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(fundingScheduledDate, "fundingScheduledDate");
        if (!fundingScheduledDate.isBefore(term.expectedDeliveryDate())) {
            throw new IllegalArgumentException("fundingScheduledDate must be before expectedDeliveryDate");
        }
        return new Milestone(milestoneId, contractId, term.milestoneIndex(), term.committedQuantity(),
                term.expectedDeliveryDate(), term.graceDays(), fundingScheduledDate, MilestoneStatus.CREATED,
                FundingStatus.NOT_REQUESTED, null, null, null, null, 0, null, null, null, null, null);
    }

    public static Milestone reconstitute(String milestoneId, String contractId, int milestoneIndex,
                                         BigDecimal committedQuantity, LocalDate expectedDeliveryDate, int graceDays,
                                         LocalDate fundingScheduledDate, MilestoneStatus status,
                                         FundingStatus fundingStatus, VndAmount effectiveUnitPrice,
                                         VndAmount batchAmount, Instant fundingRequestedAt, Instant fundingLockedAt,
                                         int fundingDelayBusinessDays, String lastFundingFailureReason,
                                         SellerWeighing sellerWeighing, BuyerReceipt buyerReceipt,
                                         MilestoneSettlement settlement) {
        return reconstitute(milestoneId, contractId, milestoneIndex, committedQuantity, expectedDeliveryDate,
                graceDays, fundingScheduledDate, status, fundingStatus, effectiveUnitPrice, batchAmount,
                fundingRequestedAt, fundingLockedAt, fundingDelayBusinessDays, lastFundingFailureReason,
                sellerWeighing, buyerReceipt, settlement, null);
    }

    public static Milestone reconstitute(String milestoneId, String contractId, int milestoneIndex,
                                         BigDecimal committedQuantity, LocalDate expectedDeliveryDate, int graceDays,
                                         LocalDate fundingScheduledDate, MilestoneStatus status,
                                         FundingStatus fundingStatus, VndAmount effectiveUnitPrice,
                                         VndAmount batchAmount, Instant fundingRequestedAt, Instant fundingLockedAt,
                                         int fundingDelayBusinessDays, String lastFundingFailureReason,
                                         SellerWeighing sellerWeighing, BuyerReceipt buyerReceipt,
                                         MilestoneSettlement settlement, MilestoneFlag flag) {
        return new Milestone(milestoneId, contractId, milestoneIndex, committedQuantity, expectedDeliveryDate,
                graceDays, fundingScheduledDate, status, fundingStatus, effectiveUnitPrice, batchAmount,
                fundingRequestedAt, fundingLockedAt, fundingDelayBusinessDays, lastFundingFailureReason,
                sellerWeighing, buyerReceipt, settlement, flag);
    }

    /** True from fundingScheduledDate on (ICT date), while funding has not been requested. */
    public boolean isFundingDue(LocalDate today) {
        return fundingStatus == FundingStatus.NOT_REQUESTED && !today.isBefore(fundingScheduledDate);
    }

    /**
     * Fixes batchAmount = committedQuantity × effectiveUnitPrice, rounded once to the whole đồng (D65), and moves to
     * FUNDING_PENDING. Called once; the caller writes milestone.funding_requested in the same transaction.
     */
    public VndAmount requestFunding(VndAmount unitPrice, Instant requestedAt) {
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (fundingStatus != FundingStatus.NOT_REQUESTED) {
            throw new InvalidMilestoneTransitionException("Milestone " + milestoneId + " funding was already requested ("
                    + fundingStatus + ")");
        }
        VndAmount amount = VndAmount.ofDong(committedQuantity.multiply(unitPrice.value()));
        if (amount.value().signum() <= 0) {
            throw new InvalidMilestoneTransitionException("Milestone " + milestoneId + " batch amount rounds to 0 VND");
        }
        this.effectiveUnitPrice = unitPrice;
        this.batchAmount = amount;
        this.fundingRequestedAt = requestedAt;
        this.fundingStatus = FundingStatus.FUNDING_PENDING;
        return amount;
    }

    /**
     * escrow.milestone_funded: LOCKED, and CREATED becomes IN_PROGRESS. delayBusinessDays is counted by the caller
     * from fundingRequestedAt to lockedAt on the Vietnam calendar. Returns false for a milestone already LOCKED (a
     * repeated report). A lock after FUNDING_FAILED is accepted: escrow retried and the buyer paid.
     */
    public boolean recordFunded(Instant lockedAt, int delayBusinessDays) {
        Objects.requireNonNull(lockedAt, "lockedAt");
        if (fundingStatus == FundingStatus.LOCKED) {
            return false;
        }
        if (fundingStatus != FundingStatus.FUNDING_PENDING && fundingStatus != FundingStatus.FUNDING_FAILED) {
            throw new InvalidMilestoneTransitionException("Milestone " + milestoneId + " cannot be funded in "
                    + fundingStatus);
        }
        if (lockedAt.isBefore(fundingRequestedAt)) {
            throw new InvalidMilestoneTransitionException("Milestone " + milestoneId + " locked before it was requested");
        }
        if (delayBusinessDays < 0) {
            throw new IllegalArgumentException("delayBusinessDays must not be negative");
        }
        this.fundingStatus = FundingStatus.LOCKED;
        this.fundingLockedAt = lockedAt;
        this.fundingDelayBusinessDays = delayBusinessDays;
        if (status == MilestoneStatus.CREATED) {
            this.status = MilestoneStatus.IN_PROGRESS;
        }
        return true;
    }

    /**
     * escrow.milestone_funding_failed: FUNDING_FAILED with the reason. Returns false when already FUNDING_FAILED. The
     * cure window and the notifications are #239.
     */
    public boolean recordFundingFailed(String reason) {
        if (fundingStatus == FundingStatus.FUNDING_FAILED) {
            return false;
        }
        if (fundingStatus != FundingStatus.FUNDING_PENDING) {
            throw new InvalidMilestoneTransitionException("Milestone " + milestoneId + " funding cannot fail in "
                    + fundingStatus);
        }
        this.fundingStatus = FundingStatus.FUNDING_FAILED;
        this.lastFundingFailureReason = reason == null || reason.length() <= MAX_FAILURE_REASON ? reason
                : reason.substring(0, MAX_FAILURE_REASON);
        return true;
    }

    /**
     * Whose turn it is to upload weighing evidence (D71, design §3.2.1): the SELLER while IN_PROGRESS, or while
     * SELLER_WEIGHED inside the correction window (D75); the BUYER while SELLER_WEIGHED. Anything else is
     * MILESTONE_ACTION_NOT_ALLOWED.
     */
    public void requireEvidenceUploadTurn(SignerRole role, Instant now, Duration correctionWindow) {
        boolean allowed = role == SignerRole.SELLER
                ? status == MilestoneStatus.IN_PROGRESS || isCorrectable(now, correctionWindow)
                : status == MilestoneStatus.SELLER_WEIGHED;
        if (!allowed) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "It is not your turn to upload evidence for this milestone (" + status + ").");
        }
    }

    /**
     * The seller declares the loaded weight (D72-D75). From IN_PROGRESS: SELLER_WEIGHED with declaration 1, whatever
     * the delivery deadline (D73). On SELLER_WEIGHED: a correction, only within {@code correctionWindow} of the first
     * declaration (a correction never extends it). Once the buyer has recorded receipt (or the milestone moved on in
     * any other way) the declaration is closed: WEIGH_CORRECTION_CLOSED. Returns the new declaration number.
     */
    public int declareSellerWeight(BigDecimal weight, String evidenceFileId, String deliveryCertificateFileId,
                                   Instant declaredAt, Duration correctionWindow) {
        Objects.requireNonNull(declaredAt, "declaredAt");
        SellerWeight.requireValid(weight);
        Objects.requireNonNull(evidenceFileId, "evidenceFileId");
        if (status == MilestoneStatus.IN_PROGRESS) {
            this.sellerWeighing = new SellerWeighing(weight, evidenceFileId, deliveryCertificateFileId, declaredAt, 1);
            this.status = MilestoneStatus.SELLER_WEIGHED;
            return 1;
        }
        if (sellerWeighing == null) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "This milestone cannot be weighed now (" + status + ").");
        }
        if (!isCorrectable(declaredAt, correctionWindow)) {
            throw new ContractConflictException(ContractConflictException.WEIGH_CORRECTION_CLOSED,
                    status == MilestoneStatus.SELLER_WEIGHED
                            ? "The correction window closed at " + correctionDeadline(correctionWindow) + "."
                            : "The buyer has already recorded receipt; the weight can no longer be corrected.");
        }
        int number = sellerWeighing.declarationCount() + 1;
        this.sellerWeighing = new SellerWeighing(weight, evidenceFileId, deliveryCertificateFileId,
                sellerWeighing.firstDeclaredAt(), number);
        return number;
    }

    /**
     * The buyer records the unloaded weight (D76 step 1): SELLER_WEIGHED becomes BUYER_RECEIVED. This closes the
     * seller's correction window at once (isCorrectable needs SELLER_WEIGHED). There is no correction: any other
     * state, a second receipt included, is MILESTONE_ACTION_NOT_ALLOWED. The weight is not compared with the seller's.
     */
    public void recordBuyerReceipt(BigDecimal weight, String evidenceFileId, String certificateFileId,
                                   Instant receivedAt) {
        Objects.requireNonNull(receivedAt, "receivedAt");
        SellerWeight.requireValid(weight);
        Objects.requireNonNull(evidenceFileId, "evidenceFileId");
        if (status != MilestoneStatus.SELLER_WEIGHED) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "The receipt can only be recorded on a SELLER_WEIGHED milestone (" + status + ").");
        }
        this.buyerReceipt = new BuyerReceipt(weight, evidenceFileId, certificateFileId, receivedAt);
        this.status = MilestoneStatus.BUYER_RECEIVED;
    }

    /**
     * Clean settlement (D77, D78): BUYER_RECEIVED becomes SETTLED with the computed money. Only from BUYER_RECEIVED
     * with a locked batch, so a second settle (or a settle after a flag) is MILESTONE_ACTION_NOT_ALLOWED.
     */
    public MilestoneSettlement settleClean(SettlementPath path, BigDecimal toleranceRate, Instant settledAt) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(settledAt, "settledAt");
        if (status != MilestoneStatus.BUYER_RECEIVED || buyerReceipt == null || sellerWeighing == null) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "Only a BUYER_RECEIVED milestone can be settled (" + status + ").");
        }
        if (batchAmount == null || effectiveUnitPrice == null) {
            throw new InvalidMilestoneTransitionException("Milestone " + milestoneId + " has no locked batch");
        }
        CleanSettlement.Result result = CleanSettlement.compute(sellerWeighing.declaredWeight(),
                buyerReceipt.receivedWeight(), toleranceRate, effectiveUnitPrice, batchAmount);
        this.settlement = new MilestoneSettlement(path, result.billableKg(), result.sellerAmount(),
                result.buyerRefund(), settledAt);
        this.status = MilestoneStatus.SETTLED;
        return settlement;
    }

    /**
     * The buyer's flag (D80, D82): BUYER_RECEIVED becomes AWAITING_SELLER_RESPONSE. Only from BUYER_RECEIVED, so a
     * second flag is MILESTONE_ACTION_NOT_ALLOWED. The confirm window and the contract state are checked by the caller.
     */
    public void flagIssue(FlagIssueType issueType, Instant flaggedAt) {
        Objects.requireNonNull(issueType, "issueType");
        Objects.requireNonNull(flaggedAt, "flaggedAt");
        if (status != MilestoneStatus.BUYER_RECEIVED || buyerReceipt == null || sellerWeighing == null) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "Only a BUYER_RECEIVED milestone can be flagged (" + status + ").");
        }
        this.flag = new MilestoneFlag(issueType, flaggedAt, null, null, null);
        this.status = MilestoneStatus.AWAITING_SELLER_RESPONSE;
    }

    /**
     * The contest step (D80, D82), by the seller or by the system for a silent QUALITY flag: AWAITING_SELLER_RESPONSE
     * becomes CONTESTED and remembers the BreachCase opened for it. Only from AWAITING_SELLER_RESPONSE.
     */
    public void contest(ContestedBy by, String breachCaseId, Instant contestedAt) {
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(breachCaseId, "breachCaseId");
        Objects.requireNonNull(contestedAt, "contestedAt");
        requireAwaitingSellerResponse("contested");
        this.flag = new MilestoneFlag(flag.issueType(), flag.flaggedAt(), contestedAt, by, breachCaseId);
        this.status = MilestoneStatus.CONTESTED;
    }

    /**
     * D82: a QUANTITY flag the seller left unanswered settles on the buyer's weight. Only from
     * AWAITING_SELLER_RESPONSE with a QUANTITY flag and a locked batch.
     */
    public MilestoneSettlement settleSellerSilentQuantity(Instant settledAt) {
        Objects.requireNonNull(settledAt, "settledAt");
        requireAwaitingSellerResponse("settled on the seller's silence");
        if (flag.issueType() != FlagIssueType.QUANTITY) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "Only a QUANTITY flag settles on the seller's silence (" + flag.issueType() + ").");
        }
        if (batchAmount == null || effectiveUnitPrice == null) {
            throw new InvalidMilestoneTransitionException("Milestone " + milestoneId + " has no locked batch");
        }
        CleanSettlement.Result result = SellerSilenceSettlement.compute(buyerReceipt.receivedWeight(),
                effectiveUnitPrice, batchAmount);
        this.settlement = new MilestoneSettlement(SettlementPath.SELLER_SILENT_QUANTITY, result.billableKg(),
                result.sellerAmount(), result.buyerRefund(), settledAt);
        this.status = MilestoneStatus.SETTLED;
        return settlement;
    }

    /**
     * Mandatory inspection (D77): the confirmed report found the goods CONFORMING. BUYER_RECEIVED becomes SETTLED on
     * the money the report computed; sellerAmount + buyerRefund must equal the locked batch.
     */
    public MilestoneSettlement settleInspectedConforming(BigDecimal acceptedQuantityKg, VndAmount sellerAmount,
                                                         VndAmount buyerRefund, Instant settledAt) {
        Objects.requireNonNull(acceptedQuantityKg, "acceptedQuantityKg");
        Objects.requireNonNull(sellerAmount, "sellerAmount");
        Objects.requireNonNull(buyerRefund, "buyerRefund");
        Objects.requireNonNull(settledAt, "settledAt");
        if (status != MilestoneStatus.BUYER_RECEIVED || buyerReceipt == null || sellerWeighing == null) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "Only a BUYER_RECEIVED milestone can be settled (" + status + ").");
        }
        if (batchAmount == null || effectiveUnitPrice == null) {
            throw new InvalidMilestoneTransitionException("Milestone " + milestoneId + " has no locked batch");
        }
        if (sellerAmount.value().add(buyerRefund.value()).compareTo(batchAmount.value()) != 0) {
            throw new InvalidMilestoneTransitionException("Settlement of milestone " + milestoneId
                    + " does not add up to the locked batch");
        }
        this.settlement = new MilestoneSettlement(SettlementPath.INSPECTED_CONFORMING,
                acceptedQuantityKg.stripTrailingZeros(), sellerAmount, buyerRefund, settledAt);
        this.status = MilestoneStatus.SETTLED;
        return settlement;
    }

    /**
     * Mandatory inspection (D77): the confirmed report found the goods PARTIALLY or NON conforming and nobody flagged.
     * BUYER_RECEIVED becomes CONTESTED by the system with the BreachCase it opened, as a QUALITY dispute.
     */
    public void contestByInspection(String breachCaseId, Instant contestedAt) {
        Objects.requireNonNull(breachCaseId, "breachCaseId");
        Objects.requireNonNull(contestedAt, "contestedAt");
        if (status != MilestoneStatus.BUYER_RECEIVED || buyerReceipt == null || sellerWeighing == null) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "Only a BUYER_RECEIVED milestone can be contested by its inspection (" + status + ").");
        }
        this.flag = new MilestoneFlag(FlagIssueType.QUALITY, contestedAt, contestedAt, ContestedBy.SYSTEM,
                breachCaseId);
        this.status = MilestoneStatus.CONTESTED;
    }

    private void requireAwaitingSellerResponse(String action) {
        if (status != MilestoneStatus.AWAITING_SELLER_RESPONSE || flag == null || buyerReceipt == null
                || sellerWeighing == null) {
            throw new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED,
                    "Only an AWAITING_SELLER_RESPONSE milestone can be " + action + " (" + status + ").");
        }
    }

    /** The buyer's flag and the contest, null until flagged. */
    public MilestoneFlag flag() {
        return flag;
    }

    /** The settlement, null until SETTLED. */
    public MilestoneSettlement settlement() {
        return settlement;
    }

    /** SELLER_WEIGHED and still before firstDeclaredAt + window (D75). */
    public boolean isCorrectable(Instant now, Duration correctionWindow) {
        return status == MilestoneStatus.SELLER_WEIGHED && sellerWeighing != null
                && now.isBefore(correctionDeadline(correctionWindow));
    }

    private Instant correctionDeadline(Duration correctionWindow) {
        return sellerWeighing.firstDeclaredAt().plus(correctionWindow);
    }

    /** The declaration in force, null before the seller weighed. */
    public SellerWeighing sellerWeighing() {
        return sellerWeighing;
    }

    /** The buyer's receipt, null before the buyer recorded it. */
    public BuyerReceipt buyerReceipt() {
        return buyerReceipt;
    }

    public String milestoneId() {
        return milestoneId;
    }

    public String contractId() {
        return contractId;
    }

    public int milestoneIndex() {
        return milestoneIndex;
    }

    public BigDecimal committedQuantity() {
        return committedQuantity;
    }

    public LocalDate expectedDeliveryDate() {
        return expectedDeliveryDate;
    }

    public int graceDays() {
        return graceDays;
    }

    public LocalDate fundingScheduledDate() {
        return fundingScheduledDate;
    }

    public MilestoneStatus status() {
        return status;
    }

    public FundingStatus fundingStatus() {
        return fundingStatus;
    }

    public VndAmount effectiveUnitPrice() {
        return effectiveUnitPrice;
    }

    public VndAmount batchAmount() {
        return batchAmount;
    }

    public Instant fundingRequestedAt() {
        return fundingRequestedAt;
    }

    public Instant fundingLockedAt() {
        return fundingLockedAt;
    }

    public int fundingDelayBusinessDays() {
        return fundingDelayBusinessDays;
    }

    public String lastFundingFailureReason() {
        return lastFundingFailureReason;
    }
}
