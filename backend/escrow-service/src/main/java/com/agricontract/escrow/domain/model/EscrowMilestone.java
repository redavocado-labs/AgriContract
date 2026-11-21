package com.agricontract.escrow.domain.model;

import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Escrow's side of one milestone's funding (#120, D63/D64/D69; milestone-escrow design §2.3, §6b). Created by
 * milestone.funding_requested; escrow locks exactly the batch amount contract-service fixed, never recomputes it.
 * Keeps the state only: the money lives in bank-service ledger_entry. Table: escrow_milestone (V12).
 * <p>
 * The bank ledger is the truth: a lock result always wins, even after the retries ran out (the buyer paid late).
 * A failure schedules a resend of the same requestEventId (D27) until the RetrySchedule is used up, then the funding
 * fails (D69: a missing balance is retried too, the buyer may top up).
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
                            Instant createdAt, Instant updatedAt) {
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
                null, 0, null, null, now, now);
    }

    /** Rebuilds a stored row; no validation, the row was valid when written. */
    public static EscrowMilestone reconstitute(String milestoneId, String contractId, int milestoneIndex,
                                               String buyerId, Money batchAmount, Instant fundingRequestedAt,
                                               String fundingRequestEventId, EscrowMilestoneStatus status,
                                               String requestEventId, String ledgerEntryId, Instant lockedAt,
                                               int failedAttempts, Instant nextAttemptAt, String lastFailureReason,
                                               Instant createdAt, Instant updatedAt) {
        return new EscrowMilestone(milestoneId, contractId, milestoneIndex, buyerId, batchAmount, fundingRequestedAt,
                fundingRequestEventId, status, requestEventId, ledgerEntryId, lockedAt, failedAttempts, nextAttemptAt,
                lastFailureReason, createdAt, updatedAt);
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
        if (status == EscrowMilestoneStatus.LOCKED) {
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
        if (status == EscrowMilestoneStatus.LOCKED) {
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
