package com.agricontract.escrow.domain.model;

import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One bank command that moves locked milestone money out of escrow on a clean settlement (#248; milestone-escrow
 * design §7.1): RELEASE_TO_SELLER for the amount the seller is paid, REFUND_TO_BUYER for the rest of the lock. Only
 * a positive amount becomes a leg; a zero side is omitted. Table: settlement_leg (V13).
 * <p>
 * requestEventId is the bank command's eventId and its sourceEventId (D27): a resend after a refusal reuses it, so
 * bank-service writes the entry once. The leg is COMPLETED only by the bank's result, never by escrow on its own.
 */
@Getter
public final class SettlementLeg {

    public static final String MILESTONE_PAYMENT = "MILESTONE_PAYMENT";

    private final String legId;
    private final String contractId;
    private final String milestoneId;
    private final String triggerEventId;           // eventId of the milestone.settled that created the leg
    private final SettlementLegType type;
    private final String fundType;
    private final String userId;
    private final Money amount;
    private final String requestEventId;           // eventId = sourceEventId of the bank command (D27)
    private SettlementLegStatus status;
    private String ledgerEntryId;
    private int failedAttempts;
    private Instant nextAttemptAt;                 // FAILED: when the same command is resent; null once exhausted
    private String lastFailureReason;
    private final Instant createdAt;
    private Instant updatedAt;

    /** What a bank refusal did. */
    public enum FailureOutcome {
        /** The same command is resent at nextAttemptAt. */
        RETRY_SCHEDULED,
        /** No resend is left: the money stays locked until OPERATOR acts. */
        RETRIES_EXHAUSTED,
        /** Already COMPLETED: the ledger holds the entry, a late refusal changes nothing. */
        IGNORED_ALREADY_COMPLETED,
        /** No command is in flight (a resend is already scheduled, or exhausted): a repeated result. */
        IGNORED_NOT_IN_FLIGHT
    }

    private SettlementLeg(String legId, String contractId, String milestoneId, String triggerEventId,
                          SettlementLegType type, String fundType, String userId, Money amount, String requestEventId,
                          SettlementLegStatus status, String ledgerEntryId, int failedAttempts, Instant nextAttemptAt,
                          String lastFailureReason, Instant createdAt, Instant updatedAt) {
        this.legId = legId;
        this.contractId = contractId;
        this.milestoneId = milestoneId;
        this.triggerEventId = triggerEventId;
        this.type = type;
        this.fundType = fundType;
        this.userId = userId;
        this.amount = amount;
        this.requestEventId = requestEventId;
        this.status = status;
        this.ledgerEntryId = ledgerEntryId;
        this.failedAttempts = failedAttempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastFailureReason = lastFailureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A new MILESTONE_PAYMENT leg, REQUESTED with a fresh bank command id. */
    static SettlementLeg requestMilestonePayment(String contractId, String milestoneId, String triggerEventId,
                                                 SettlementLegType type, String userId, Money amount, Instant now) {
        requireUuid("userId", userId);
        requireUuid("triggerEventId", triggerEventId);
        return new SettlementLeg(UUID.randomUUID().toString(), contractId, milestoneId, triggerEventId, type,
                MILESTONE_PAYMENT, userId, amount, UUID.randomUUID().toString(), SettlementLegStatus.REQUESTED, null,
                0, null, null, now, now);
    }

    /** Rebuilds a stored row; no validation, the row was valid when written. */
    public static SettlementLeg reconstitute(String legId, String contractId, String milestoneId,
                                             String triggerEventId, SettlementLegType type, String fundType,
                                             String userId, Money amount, String requestEventId,
                                             SettlementLegStatus status, String ledgerEntryId, int failedAttempts,
                                             Instant nextAttemptAt, String lastFailureReason, Instant createdAt,
                                             Instant updatedAt) {
        return new SettlementLeg(legId, contractId, milestoneId, triggerEventId, type, fundType, userId, amount,
                requestEventId, status, ledgerEntryId, failedAttempts, nextAttemptAt, lastFailureReason, createdAt,
                updatedAt);
    }

    /** The bank result names this leg's command exactly: same user, operation, fund and amount. */
    public boolean matchesResult(String userId, String entryType, String fundType, Money amount) {
        return this.userId.equals(userId) && type.name().equals(entryType) && this.fundType.equals(fundType)
                && this.amount.equals(amount);
    }

    /** bank.*_completed. False for a duplicate result. A completion after a refusal still counts: the ledger wins. */
    public boolean markCompleted(String ledgerEntryId, Instant now) {
        requireUuid("ledgerEntryId", ledgerEntryId);
        if (status == SettlementLegStatus.COMPLETED) {
            return false;
        }
        this.status = SettlementLegStatus.COMPLETED;
        this.ledgerEntryId = ledgerEntryId;
        this.nextAttemptAt = null;
        this.updatedAt = now;
        return true;
    }

    /** bank.*_failed: counts the refusal of the command in flight and schedules its resend, or gives up. */
    public FailureOutcome markFailed(String reason, Instant now, RetrySchedule schedule) {
        if (status == SettlementLegStatus.COMPLETED) {
            return FailureOutcome.IGNORED_ALREADY_COMPLETED;
        }
        if (status != SettlementLegStatus.REQUESTED) {
            return FailureOutcome.IGNORED_NOT_IN_FLIGHT;
        }
        this.status = SettlementLegStatus.FAILED;
        this.failedAttempts += 1;
        this.lastFailureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
        this.nextAttemptAt = schedule.nextAttempt(failedAttempts, now).orElse(null);
        this.updatedAt = now;
        return nextAttemptAt == null ? FailureOutcome.RETRIES_EXHAUSTED : FailureOutcome.RETRY_SCHEDULED;
    }

    /** A refused leg whose resend time has come. */
    public boolean isRetryDue(Instant now) {
        return status == SettlementLegStatus.FAILED && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
    }

    /** The same command is being sent again: in flight until its result arrives. */
    public void retryRequested(Instant now) {
        if (!isRetryDue(now)) {
            throw new IllegalStateException("Settlement leg " + legId + " has no retry due (" + status + ")");
        }
        this.status = SettlementLegStatus.REQUESTED;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public boolean isCompleted() {
        return status == SettlementLegStatus.COMPLETED;
    }

    static void requireUuid(String field, String value) {
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
