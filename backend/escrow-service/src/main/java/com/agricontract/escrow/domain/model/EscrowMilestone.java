package com.agricontract.escrow.domain.model;

import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Escrow's side of one milestone's funding (#120, D63/D64/D69; milestone-escrow design §2.3, §6b). Created by
 * milestone.funding_requested; escrow locks exactly the batch amount contract-service fixed, never recomputes it.
 * Keeps the state only: the money lives in bank-service ledger_entry. Table: escrow_milestone (V12).
 * <p>
 * The bank ledger is the truth: a lock result always wins, even after the retries ran out (the buyer paid late).
 * A failure schedules a resend of the same requestEventId (D27) until the RetrySchedule is used up, then the funding
 * fails (D69: a missing balance is retried too, the buyer may top up).
 * <p>
 * Clean settlement (#248, design §7.1): milestone.settled turns the lock into settlement legs (RELEASE_TO_SELLER the
 * actual amount, REFUND_TO_BUYER the rest; a zero side is omitted) and the milestone is SETTLING. It is SETTLED only
 * when the bank confirmed every leg: escrow's view of the money moves on bank results, never on its own commands.
 */
@Getter
public final class EscrowMilestone {

    private final String milestoneId;
    private final String contractId;
    private final int milestoneIndex;
    private final String buyerId;
    private final Money batchAmount;
    private final Instant fundingRequestedAt;      // requestedAt of milestone.funding_requested
    private final String fundingRequestEventId;    // eventId of that milestone.funding_requested
    private EscrowMilestoneStatus status;
    private final String requestEventId;           // eventId = sourceEventId of bank.lock_requested (D27)
    private String ledgerEntryId;
    private Instant lockedAt;
    private int failedAttempts;
    private Instant nextAttemptAt;                 // FUNDING_PENDING after a failure: when the resend is due
    private String lastFailureReason;
    private String settlementEventId;              // eventId of the milestone.settled that started the settlement
    private Instant settledAt;                     // when the bank confirmed the last settlement leg
    private final Instant createdAt;
    private Instant updatedAt;

    /** What a bank.lock_completed did. */
    public enum LockOutcome {
        /** FUNDING_PENDING → LOCKED: escrow.milestone_funded must be written. */
        LOCKED,
        /** FUNDING_FAILED → LOCKED: the bank locked after the retries ran out. Still funded: the ledger is the truth. */
        LOCKED_AFTER_FAILURE,
        /** Already LOCKED: a duplicate result, nothing to do. */
        DUPLICATE
    }

    /** What a bank.lock_failed did. */
    public enum FailureOutcome {
        /** A resend of the same request is scheduled at nextAttemptAt. */
        RETRY_SCHEDULED,
        /** No resend is left: FUNDING_FAILED, escrow.milestone_funding_failed must be written. */
        RETRIES_EXHAUSTED,
        /** Already LOCKED: the bank ledger holds the money, a late failure changes nothing. */
        IGNORED_ALREADY_LOCKED,
        /** No request is in flight (a resend is already scheduled, or the funding failed): a repeated result. */
        IGNORED_NOT_IN_FLIGHT
    }

    private EscrowMilestone(String milestoneId, String contractId, int milestoneIndex, String buyerId,
                            Money batchAmount, Instant fundingRequestedAt, String fundingRequestEventId,
                            EscrowMilestoneStatus status, String requestEventId, String ledgerEntryId,
                            Instant lockedAt, int failedAttempts, Instant nextAttemptAt, String lastFailureReason,
                            String settlementEventId, Instant settledAt, Instant createdAt, Instant updatedAt) {
        this.milestoneId = milestoneId;
        this.contractId = contractId;
        this.milestoneIndex = milestoneIndex;
        this.buyerId = buyerId;
        this.batchAmount = batchAmount;
        this.fundingRequestedAt = fundingRequestedAt;
        this.fundingRequestEventId = fundingRequestEventId;
        this.status = status;
        this.requestEventId = requestEventId;
        this.ledgerEntryId = ledgerEntryId;
        this.lockedAt = lockedAt;
        this.failedAttempts = failedAttempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastFailureReason = lastFailureReason;
        this.settlementEventId = settlementEventId;
        this.settledAt = settledAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** milestone.funding_requested: FUNDING_PENDING with a fresh requestEventId for the bank lock. */
    public static EscrowMilestone requestFunding(String milestoneId, String contractId, int milestoneIndex,
                                                 String buyerId, Money batchAmount, Instant fundingRequestedAt,
                                                 String fundingRequestEventId, Instant now) {
        requireUuid("milestoneId", milestoneId);
        requireUuid("contractId", contractId);
        requireUuid("buyerId", buyerId);
        requireUuid("fundingRequestEventId", fundingRequestEventId);
        if (milestoneIndex < 1 || milestoneIndex > 100) {
            throw new InvalidDepositLegException("milestoneIndex must be 1..100: " + milestoneIndex);
        }
        if (batchAmount == null) {
            throw new InvalidDepositLegException("batchAmount is required");
        }
        if (fundingRequestedAt == null || now == null) {
            throw new InvalidDepositLegException("fundingRequestedAt and now are required");
        }
        return new EscrowMilestone(milestoneId, contractId, milestoneIndex, buyerId, batchAmount, fundingRequestedAt,
                fundingRequestEventId, EscrowMilestoneStatus.FUNDING_PENDING, UUID.randomUUID().toString(), null,
                null, 0, null, null, null, null, now, now);
    }

    /** Rebuilds a stored row; no validation, the row was valid when written. */
    public static EscrowMilestone reconstitute(String milestoneId, String contractId, int milestoneIndex,
                                               String buyerId, Money batchAmount, Instant fundingRequestedAt,
                                               String fundingRequestEventId, EscrowMilestoneStatus status,
                                               String requestEventId, String ledgerEntryId, Instant lockedAt,
                                               int failedAttempts, Instant nextAttemptAt, String lastFailureReason,
                                               String settlementEventId, Instant settledAt, Instant createdAt,
                                               Instant updatedAt) {
        return new EscrowMilestone(milestoneId, contractId, milestoneIndex, buyerId, batchAmount, fundingRequestedAt,
                fundingRequestEventId, status, requestEventId, ledgerEntryId, lockedAt, failedAttempts, nextAttemptAt,
                lastFailureReason, settlementEventId, settledAt, createdAt, updatedAt);
    }

    /** A second funding request for this milestone asks for the same lock (same buyer and amount). */
    public boolean isSameRequest(String contractId, String buyerId, Money batchAmount) {
        return this.contractId.equals(contractId) && this.buyerId.equals(buyerId)
                && this.batchAmount.equals(batchAmount);
    }

    /**
     * bank.lock_completed. lockedAt is the bank's time of the lock, never before the funding was requested: the two
     * clocks belong to different services, and contract-service counts the funding delay from requestedAt.
     */
    public LockOutcome markLocked(String ledgerEntryId, Instant bankLockedAt, Instant now) {
        requireUuid("ledgerEntryId", ledgerEntryId);
        if (bankLockedAt == null) {
            throw new InvalidDepositLegException("bankLockedAt is required");
        }
        if (holdsLock()) {
            return LockOutcome.DUPLICATE;
        }
        boolean failedBefore = status == EscrowMilestoneStatus.FUNDING_FAILED;
        this.status = EscrowMilestoneStatus.LOCKED;
        this.ledgerEntryId = ledgerEntryId;
        this.lockedAt = bankLockedAt.isBefore(fundingRequestedAt) ? fundingRequestedAt : bankLockedAt;
        this.nextAttemptAt = null;
        this.updatedAt = now;
        return failedBefore ? LockOutcome.LOCKED_AFTER_FAILURE : LockOutcome.LOCKED;
    }

    /** bank.lock_failed: counts the failure of the request in flight and schedules its resend, or fails. */
    public FailureOutcome markFailed(String reason, Instant now, RetrySchedule schedule) {
        if (holdsLock()) {
            return FailureOutcome.IGNORED_ALREADY_LOCKED;
        }
        if (!isInFlight()) {
            return FailureOutcome.IGNORED_NOT_IN_FLIGHT;
        }
        this.failedAttempts += 1;
        this.lastFailureReason = clip(reason);
        this.nextAttemptAt = schedule.nextAttempt(failedAttempts, now).orElse(null);
        this.updatedAt = now;
        if (nextAttemptAt == null) {
            this.status = EscrowMilestoneStatus.FUNDING_FAILED;
            return FailureOutcome.RETRIES_EXHAUSTED;
        }
        return FailureOutcome.RETRY_SCHEDULED;
    }

    /** A failed request whose resend time has come. */
    public boolean isRetryDue(Instant now) {
        return status == EscrowMilestoneStatus.FUNDING_PENDING && nextAttemptAt != null
                && !now.isBefore(nextAttemptAt);
    }

    /** The same request is being sent again: in flight until its result arrives. */
    public void retryRequested(Instant now) {
        if (!isRetryDue(now)) {
            throw new IllegalStateException("Milestone " + milestoneId + " has no funding retry due (" + status + ")");
        }
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /** A bank.lock_requested is out and its result has not arrived. */
    public boolean isInFlight() {
        return status == EscrowMilestoneStatus.FUNDING_PENDING && nextAttemptAt == null;
    }

    /**
     * milestone.settled on the clean path: lockedAmount must be this milestone's batch and actualAmount (computed and
     * rounded by contract-service, never recomputed here) within it. Returns the legs to send, each with a positive
     * amount: the seller gets actualAmount, the buyer lockedAmount - actualAmount. Nothing is created or lost: the
     * legs add up to the lock exactly. Only a LOCKED milestone settles; the caller handles a repeat.
     */
    public List<SettlementLeg> settle(String contractId, Money lockedAmount, BigDecimal actualAmount,
                                      String sellerId, String settlementEventId, Instant now) {
        if (status != EscrowMilestoneStatus.LOCKED) {
            throw new InvalidDepositLegException("Milestone " + milestoneId + " cannot settle: it is " + status);
        }
        if (!this.contractId.equals(contractId)) {
            throw new InvalidDepositLegException("Milestone " + milestoneId + " belongs to contract "
                    + this.contractId + ", not " + contractId);
        }
        Map<SettlementLegType, BigDecimal> amounts = legAmounts(lockedAmount, actualAmount);
        if (amounts.containsKey(SettlementLegType.RELEASE_TO_SELLER) && sellerId == null) {
            throw new InvalidDepositLegException("Milestone " + milestoneId + " pays the seller but names no seller");
        }
        List<SettlementLeg> legs = new ArrayList<>();
        amounts.forEach((type, amount) -> legs.add(SettlementLeg.requestMilestonePayment(contractId, milestoneId,
                settlementEventId, type, type == SettlementLegType.RELEASE_TO_SELLER ? sellerId : buyerId,
                new Money(amount), now)));
        this.status = EscrowMilestoneStatus.SETTLING;
        this.settlementEventId = settlementEventId;
        this.updatedAt = now;
        return legs;
    }

    /**
     * A second milestone.settled (another eventId) asks for exactly the settlement already started: same contract,
     * same lock, and its legs would be the stored ones (same amounts, same seller).
     */
    public boolean isSameSettlement(List<SettlementLeg> legs, String contractId, Money lockedAmount,
                                    BigDecimal actualAmount, String sellerId) {
        if (!this.contractId.equals(contractId) || !batchAmount.equals(lockedAmount) || actualAmount == null
                || actualAmount.signum() < 0 || actualAmount.compareTo(lockedAmount.amount()) > 0) {
            return false;
        }
        Map<SettlementLegType, BigDecimal> expected = legAmounts(lockedAmount, actualAmount);
        if (legs.size() != expected.size()) {
            return false;
        }
        for (SettlementLeg leg : legs) {
            BigDecimal amount = expected.get(leg.getType());
            if (amount == null || amount.compareTo(leg.getAmount().amount()) != 0
                    || (leg.getType() == SettlementLegType.RELEASE_TO_SELLER && !leg.getUserId().equals(sellerId))) {
                return false;
            }
        }
        return true;
    }

    /** SETTLING → SETTLED once the bank confirmed every leg. True when this call settled it. */
    public boolean markSettledIfComplete(List<SettlementLeg> legs, Instant now) {
        if (status != EscrowMilestoneStatus.SETTLING || legs.isEmpty()
                || !legs.stream().allMatch(SettlementLeg::isCompleted)) {
            return false;
        }
        this.status = EscrowMilestoneStatus.SETTLED;
        this.settledAt = now;
        this.updatedAt = now;
        return true;
    }

    /** milestone.settled was accepted for this milestone (legs sent, or all confirmed). */
    public boolean isSettlementStarted() {
        return status == EscrowMilestoneStatus.SETTLING || status == EscrowMilestoneStatus.SETTLED;
    }

    /** The bank ledger holds (or held, then paid out) this milestone's batch. */
    private boolean holdsLock() {
        return status == EscrowMilestoneStatus.LOCKED || isSettlementStarted();
    }

    /** The positive leg amounts of a settlement; validates the lock and the actual amount first. */
    private Map<SettlementLegType, BigDecimal> legAmounts(Money lockedAmount, BigDecimal actualAmount) {
        if (lockedAmount == null || !batchAmount.equals(lockedAmount)) {
            throw new InvalidDepositLegException("Milestone " + milestoneId + " locked "
                    + batchAmount.amount().toPlainString() + ", milestone.settled says "
                    + (lockedAmount == null ? null : lockedAmount.amount().toPlainString()));
        }
        if (actualAmount == null || actualAmount.signum() < 0 || actualAmount.scale() > 2
                || actualAmount.compareTo(lockedAmount.amount()) > 0) {
            throw new InvalidDepositLegException("Milestone " + milestoneId + " actualAmount "
                    + (actualAmount == null ? null : actualAmount.toPlainString()) + " is outside 0.."
                    + lockedAmount.amount().toPlainString());
        }
        Map<SettlementLegType, BigDecimal> amounts = new EnumMap<>(SettlementLegType.class);
        if (actualAmount.signum() > 0) {
            amounts.put(SettlementLegType.RELEASE_TO_SELLER, actualAmount);
        }
        BigDecimal refund = lockedAmount.amount().subtract(actualAmount);
        if (refund.signum() > 0) {
            amounts.put(SettlementLegType.REFUND_TO_BUYER, refund);
        }
        return amounts;
    }

    private static String clip(String reason) {
        return reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    private static void requireUuid(String field, String value) {
        if (value == null) {
            throw new InvalidDepositLegException(field + " is required");
        }
        try {
            if (!UUID.fromString(value).toString().equals(value.toLowerCase())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidDepositLegException(field + " is not a UUID: " + value);
        }
    }
}
