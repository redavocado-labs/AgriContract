package com.agricontract.escrow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One milestone's funding (#120, D69): lock, retries, failure, late results. */
class EscrowMilestoneTest {

    static final Instant REQUESTED_AT = Instant.parse("2026-11-20T01:00:00Z");
    static final Instant NOW = Instant.parse("2026-11-20T01:00:05Z");
    static final RetrySchedule SCHEDULE = new RetrySchedule(
            List.of(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)));
    static final String CONTRACT = UUID.randomUUID().toString();
    static final String BUYER = UUID.randomUUID().toString();

    @Test
    void request_isPendingAndInFlight_withItsOwnBankRequestId() {
        EscrowMilestone milestone = pending();

        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.FUNDING_PENDING);
        assertThat(milestone.isInFlight()).isTrue();
        assertThat(milestone.getRequestEventId()).isNotEqualTo(milestone.getFundingRequestEventId());
        assertThat(milestone.getFailedAttempts()).isZero();
    }

    @Test
    void request_refusesAnIndexOutOfRange() {
        assertThatThrownBy(() -> EscrowMilestone.requestFunding(UUID.randomUUID().toString(), CONTRACT, 0, BUYER,
                money("1000.00"), REQUESTED_AT, UUID.randomUUID().toString(), NOW))
                .isInstanceOf(InvalidDepositLegException.class);
    }

    @Test
    void lock_takesTheBankTime() {
        EscrowMilestone milestone = pending();
        Instant bankTime = REQUESTED_AT.plusSeconds(3);

        assertThat(milestone.markLocked(UUID.randomUUID().toString(), bankTime, NOW))
                .isEqualTo(EscrowMilestone.LockOutcome.LOCKED);
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.LOCKED);
        assertThat(milestone.getLockedAt()).isEqualTo(bankTime);
    }

    @Test
    void lock_neverBeforeTheRequest_whenTheBankClockIsBehind() {
        EscrowMilestone milestone = pending();

        milestone.markLocked(UUID.randomUUID().toString(), REQUESTED_AT.minusSeconds(2), NOW);

        assertThat(milestone.getLockedAt()).isEqualTo(REQUESTED_AT);
    }

    @Test
    void secondLock_isADuplicate_andKeepsTheFirstEntry() {
        EscrowMilestone milestone = pending();
        String first = UUID.randomUUID().toString();
        milestone.markLocked(first, NOW, NOW);

        assertThat(milestone.markLocked(UUID.randomUUID().toString(), NOW.plusSeconds(9), NOW))
                .isEqualTo(EscrowMilestone.LockOutcome.DUPLICATE);
        assertThat(milestone.getLedgerEntryId()).isEqualTo(first);
    }

    @Test
    void failure_schedulesTheResend_perTheBackoff() {
        EscrowMilestone milestone = pending();

        assertThat(milestone.markFailed("INSUFFICIENT_BALANCE", NOW, SCHEDULE))
                .isEqualTo(EscrowMilestone.FailureOutcome.RETRY_SCHEDULED);
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.FUNDING_PENDING);
        assertThat(milestone.isInFlight()).isFalse();
        assertThat(milestone.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(milestone.isRetryDue(NOW.plus(Duration.ofMinutes(4)))).isFalse();
        assertThat(milestone.isRetryDue(NOW.plus(Duration.ofMinutes(5)))).isTrue();
    }

    @Test
    void repeatedFailureOfTheSameAttempt_isNotCountedTwice() {
        EscrowMilestone milestone = pending();
        milestone.markFailed("INSUFFICIENT_BALANCE", NOW, SCHEDULE);

        assertThat(milestone.markFailed("INSUFFICIENT_BALANCE", NOW.plusSeconds(1), SCHEDULE))
                .isEqualTo(EscrowMilestone.FailureOutcome.IGNORED_NOT_IN_FLIGHT);
        assertThat(milestone.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void failureAfterTheLastRetry_failsTheFunding_withTheLastReason() {
        EscrowMilestone milestone = pending();
        Instant t = NOW;
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(milestone.markFailed("INSUFFICIENT_BALANCE", t, SCHEDULE))
                    .isEqualTo(EscrowMilestone.FailureOutcome.RETRY_SCHEDULED);
            t = milestone.getNextAttemptAt();
            milestone.retryRequested(t);
        }

        assertThat(milestone.markFailed("SYSTEM_LOCK_ACTIVE", t, SCHEDULE))
                .isEqualTo(EscrowMilestone.FailureOutcome.RETRIES_EXHAUSTED);
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.FUNDING_FAILED);
        assertThat(milestone.getFailedAttempts()).isEqualTo(4);
        assertThat(milestone.getLastFailureReason()).isEqualTo("SYSTEM_LOCK_ACTIVE");
        assertThat(milestone.getNextAttemptAt()).isNull();
        assertThat(milestone.markFailed("x", t, SCHEDULE))
                .isEqualTo(EscrowMilestone.FailureOutcome.IGNORED_NOT_IN_FLIGHT);
    }

    @Test
    void lockAfterTheFundingFailed_stillFundsTheMilestone() {
        EscrowMilestone milestone = exhausted();

        assertThat(milestone.markLocked(UUID.randomUUID().toString(), NOW.plusSeconds(60), NOW.plusSeconds(60)))
                .isEqualTo(EscrowMilestone.LockOutcome.LOCKED_AFTER_FAILURE);
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.LOCKED);
    }

    @Test
    void lateFailureForALockedFunding_isIgnored() {
        EscrowMilestone milestone = pending();
        milestone.markLocked(UUID.randomUUID().toString(), NOW, NOW);

        assertThat(milestone.markFailed("SYSTEM_LOCK_ACTIVE", NOW, SCHEDULE))
                .isEqualTo(EscrowMilestone.FailureOutcome.IGNORED_ALREADY_LOCKED);
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.LOCKED);
    }

    @Test
    void retry_refusedWhenNotDue() {
        EscrowMilestone milestone = pending();

        assertThatThrownBy(() -> milestone.retryRequested(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameRequest_comparesContractBuyerAndAmount() {
        EscrowMilestone milestone = pending();

        assertThat(milestone.isSameRequest(CONTRACT, BUYER, money("300000000.00"))).isTrue();
        assertThat(milestone.isSameRequest(CONTRACT, BUYER, money("320000000.00"))).isFalse();
        assertThat(milestone.isSameRequest(CONTRACT, UUID.randomUUID().toString(), money("300000000.00"))).isFalse();
    }

    private static EscrowMilestone pending() {
        return EscrowMilestone.requestFunding(UUID.randomUUID().toString(), CONTRACT, 1, BUYER,
                money("300000000.00"), REQUESTED_AT, UUID.randomUUID().toString(), NOW);
    }

    private static EscrowMilestone exhausted() {
        EscrowMilestone milestone = pending();
        Instant t = NOW;
        for (int attempt = 1; attempt <= 3; attempt++) {
            milestone.markFailed("INSUFFICIENT_BALANCE", t, SCHEDULE);
            t = milestone.getNextAttemptAt();
            milestone.retryRequested(t);
        }
        milestone.markFailed("INSUFFICIENT_BALANCE", t, SCHEDULE);
        return milestone;
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }
}
