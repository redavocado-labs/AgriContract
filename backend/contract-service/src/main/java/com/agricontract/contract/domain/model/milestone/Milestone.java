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

    private Milestone(String milestoneId, String contractId, int milestoneIndex, BigDecimal committedQuantity,
                      LocalDate expectedDeliveryDate, int graceDays, LocalDate fundingScheduledDate,
                      MilestoneStatus status, FundingStatus fundingStatus, VndAmount effectiveUnitPrice,
                      VndAmount batchAmount, Instant fundingRequestedAt, Instant fundingLockedAt,
                      int fundingDelayBusinessDays, String lastFundingFailureReason,
                      SellerWeighing sellerWeighing) {
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
                FundingStatus.NOT_REQUESTED, null, null, null, null, 0, null, null);
    }

    public static Milestone reconstitute(String milestoneId, String contractId, int milestoneIndex,
                                         BigDecimal committedQuantity, LocalDate expectedDeliveryDate, int graceDays,
                                         LocalDate fundingScheduledDate, MilestoneStatus status,
                                         FundingStatus fundingStatus, VndAmount effectiveUnitPrice,
                                         VndAmount batchAmount, Instant fundingRequestedAt, Instant fundingLockedAt,
                                         int fundingDelayBusinessDays, String lastFundingFailureReason,
                                         SellerWeighing sellerWeighing) {
        return new Milestone(milestoneId, contractId, milestoneIndex, committedQuantity, expectedDeliveryDate,
                graceDays, fundingScheduledDate, status, fundingStatus, effectiveUnitPrice, batchAmount,
                fundingRequestedAt, fundingLockedAt, fundingDelayBusinessDays, lastFundingFailureReason,
                sellerWeighing);
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
