package com.agricontract.escrow.domain.model;

import lombok.Getter;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One bank command of an accepted remedy decision (#249): release, refund or seize of a leg of remedy.finalized.
 * Table: remedy_leg (V14). Same life as SettlementLeg: requestEventId is the command's eventId and sourceEventId (D27),
 * a resend after a refusal reuses it; the leg is COMPLETED only by the bank's result.
 */
@Getter
public final class RemedyLeg {

    private final String remedyLegId;
    private final String remedyDecisionId;
    private final String contractId;
    private final String milestoneId;               // null for a deposit leg
    private final RemedyType remedyType;
    private final RemedyEntryType entryType;
    private final String fundType;
    private final String userId;
    private final String payeeRole;                 // BUYER or SELLER (D86); null on a row written before #609
    private final Money amount;
    private final String requestEventId;            // eventId = sourceEventId of the bank command (D27)
    private RemedyLegStatus status;
    private int attempts;                           // bank refusals so far
    private Instant nextAttemptAt;                  // FAILED: when the same command is resent; null once exhausted
    private String failureReason;
    private String ledgerEntryId;
    private final Instant createdAt;
    private Instant updatedAt;

    /** What a bank refusal did. */
    public enum FailureOutcome {
        RETRY_SCHEDULED,
        RETRIES_EXHAUSTED,
        IGNORED_ALREADY_COMPLETED,
        IGNORED_NOT_IN_FLIGHT
    }

    private RemedyLeg(String remedyLegId, String remedyDecisionId, String contractId, String milestoneId,
                     RemedyType remedyType, RemedyEntryType entryType, String fundType, String userId,
                     String payeeRole, Money amount, String requestEventId, RemedyLegStatus status, int attempts, Instant nextAttemptAt,
                     String failureReason, String ledgerEntryId, Instant createdAt, Instant updatedAt) {
        this.remedyLegId = remedyLegId;
        this.remedyDecisionId = remedyDecisionId;
        this.contractId = contractId;
        this.milestoneId = milestoneId;
        this.remedyType = remedyType;
        this.entryType = entryType;
        this.fundType = fundType;
        this.userId = userId;
        this.payeeRole = payeeRole;
        this.amount = amount;
        this.requestEventId = requestEventId;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.failureReason = failureReason;
        this.ledgerEntryId = ledgerEntryId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A new leg whose command is being sent, with a fresh command id. */
    public static RemedyLeg send(String remedyDecisionId, String contractId, PlannedRemedyLeg planned, Instant now) {
        return new RemedyLeg(planned.remedyLegId(), remedyDecisionId, contractId, planned.milestoneId(),
                planned.remedyType(), planned.entryType(), planned.fundType(), planned.userId(), planned.payeeRole(),
                new Money(planned.amount()), UUID.randomUUID().toString(), RemedyLegStatus.SENT, 0, null, null, null,
                now, now);
    }

    /** Rebuilds a stored row; no validation, the row was valid when written. */
    public static RemedyLeg reconstitute(String remedyLegId, String remedyDecisionId, String contractId,
                                         String milestoneId, RemedyType remedyType, RemedyEntryType entryType,
                                         String fundType, String userId, String payeeRole, Money amount,
                                         String requestEventId,
                                         RemedyLegStatus status, int attempts, Instant nextAttemptAt,
                                         String failureReason, String ledgerEntryId, Instant createdAt,
                                         Instant updatedAt) {
        return new RemedyLeg(remedyLegId, remedyDecisionId, contractId, milestoneId, remedyType, entryType, fundType,
                userId, payeeRole, amount, requestEventId, status, attempts, nextAttemptAt, failureReason, ledgerEntryId,
                createdAt, updatedAt);
    }

    /** This leg is the same bank leg as the planned one (a repeated decision with the same legs). */
    public boolean sameAs(PlannedRemedyLeg planned) {
        return remedyLegId.equals(planned.remedyLegId()) && entryType == planned.entryType()
                && fundType.equals(planned.fundType()) && userId.equals(planned.userId())
                && java.util.Objects.equals(milestoneId, planned.milestoneId())
                && amount.amount().compareTo(planned.amount()) == 0;
    }

    /** The bank result names this leg's command exactly. */
    public boolean matchesResult(String contractId, String userId, String entryType, String fundType, Money amount) {
        return this.contractId.equals(contractId) && this.userId.equals(userId)
                && this.entryType.name().equals(entryType) && this.fundType.equals(fundType)
                && this.amount.equals(amount);
    }

    /** bank.*_completed. False for a duplicate result. A completion after a refusal still counts: the ledger wins. */
    public boolean markCompleted(String ledgerEntryId, Instant now) {
        if (status == RemedyLegStatus.COMPLETED) {
            return false;
        }
        this.status = RemedyLegStatus.COMPLETED;
        this.ledgerEntryId = ledgerEntryId;
        this.nextAttemptAt = null;
        this.updatedAt = now;
        return true;
    }

    /** bank.*_failed: counts the refusal of the command in flight and schedules its resend, or gives up. */
    public FailureOutcome markFailed(String reason, Instant now, RetrySchedule schedule) {
        if (status == RemedyLegStatus.COMPLETED) {
            return FailureOutcome.IGNORED_ALREADY_COMPLETED;
        }
        if (status != RemedyLegStatus.SENT) {
            return FailureOutcome.IGNORED_NOT_IN_FLIGHT;
        }
        this.status = RemedyLegStatus.FAILED;
        this.attempts += 1;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
        this.nextAttemptAt = schedule.nextAttempt(attempts, now).orElse(null);
        this.updatedAt = now;
        return nextAttemptAt == null ? FailureOutcome.RETRIES_EXHAUSTED : FailureOutcome.RETRY_SCHEDULED;
    }

    public boolean isRetryDue(Instant now) {
        return status == RemedyLegStatus.FAILED && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
    }

    /** The same command is being sent again: in flight until its result arrives. */
    public void retryRequested(Instant now) {
        if (!isRetryDue(now)) {
            throw new IllegalStateException("Remedy leg " + remedyLegId + " has no retry due (" + status + ")");
        }
        this.status = RemedyLegStatus.SENT;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /**
     * OPERATOR RetryRemedyLegs (contract.remedy_retry_requested, D93): a FAILED leg, retry still scheduled or
     * exhausted, starts a new retry cycle: in flight again, refusals counted from zero, its last failure reason kept
     * until the bank answers. The caller resends the same command (same requestEventId, D27). False, and nothing
     * changes, for a SENT leg (its result is still coming) or a COMPLETED one (never resent).
     */
    public boolean restartRetryCycle(Instant now) {
        if (status != RemedyLegStatus.FAILED) {
            return false;
        }
        this.status = RemedyLegStatus.SENT;
        this.attempts = 0;
        this.nextAttemptAt = null;
        this.updatedAt = now;
        return true;
    }

    /**
     * The role receiving the leg's money: the stored payeeRole, or for a row written before it was stored, the one
     * payee the entry type and fund allow (RELEASE_TO_SELLER: SELLER, REFUND_TO_BUYER: BUYER, DEPOSIT_FORFEITURE of
     * SELLER_DEPOSIT: BUYER, of BUYER_DEPOSIT: SELLER). Empty when either party could receive it (penalty, damages).
     */
    public Optional<String> payeeParty() {
        if (payeeRole != null) {
            return Optional.of(payeeRole);
        }
        return switch (entryType) {
            case RELEASE_TO_SELLER -> Optional.of("SELLER");
            case REFUND_TO_BUYER -> Optional.of("BUYER");
            case DEPOSIT_FORFEITURE -> switch (fundType) {
                case "SELLER_DEPOSIT" -> Optional.of("BUYER");
                case "BUYER_DEPOSIT" -> Optional.of("SELLER");
                default -> Optional.empty();
            };
            default -> Optional.empty();
        };
    }
}
