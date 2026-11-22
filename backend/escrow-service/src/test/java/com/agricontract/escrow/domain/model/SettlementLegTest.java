package com.agricontract.escrow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One settlement leg's bank results (#248): completion, refusal with resend of the same command, late results. */
class SettlementLegTest {

    static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    static final RetrySchedule SCHEDULE = new RetrySchedule(List.of(Duration.ofMinutes(5), Duration.ofMinutes(30)));

    @Test
    void completion_isFinal_andADuplicateChangesNothing() {
        SettlementLeg leg = leg();
        String entry = UUID.randomUUID().toString();

        assertThat(leg.markCompleted(entry, NOW)).isTrue();
        assertThat(leg.markCompleted(UUID.randomUUID().toString(), NOW)).isFalse();

        assertThat(leg.getStatus()).isEqualTo(SettlementLegStatus.COMPLETED);
        assertThat(leg.getLedgerEntryId()).isEqualTo(entry);
        assertThat(leg.markFailed("LATE", NOW, SCHEDULE))
                .isEqualTo(SettlementLeg.FailureOutcome.IGNORED_ALREADY_COMPLETED);
    }

    @Test
    void refusal_schedulesAResendOfTheSameCommand_thenGivesUp() {
        SettlementLeg leg = leg();
        String requestId = leg.getRequestEventId();

        assertThat(leg.markFailed("KILL_SWITCH", NOW, SCHEDULE))
                .isEqualTo(SettlementLeg.FailureOutcome.RETRY_SCHEDULED);
        assertThat(leg.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(leg.markFailed("AGAIN", NOW, SCHEDULE)).as("nothing in flight")
                .isEqualTo(SettlementLeg.FailureOutcome.IGNORED_NOT_IN_FLIGHT);
        assertThat(leg.isRetryDue(NOW)).isFalse();

        Instant due = NOW.plus(Duration.ofMinutes(5));
        assertThat(leg.isRetryDue(due)).isTrue();
        leg.retryRequested(due);
        assertThat(leg.getStatus()).isEqualTo(SettlementLegStatus.REQUESTED);
        assertThat(leg.getRequestEventId()).isEqualTo(requestId);

        leg.markFailed("KILL_SWITCH", due, SCHEDULE);
        leg.retryRequested(due.plus(Duration.ofMinutes(30)));
        assertThat(leg.markFailed("KILL_SWITCH", due, SCHEDULE))
                .isEqualTo(SettlementLeg.FailureOutcome.RETRIES_EXHAUSTED);
        assertThat(leg.getNextAttemptAt()).isNull();
        assertThat(leg.getFailedAttempts()).isEqualTo(3);
    }

    @Test
    void completionAfterTheRetriesRanOut_stillCounts() {
        SettlementLeg leg = leg();
        leg.markFailed("X", NOW, new RetrySchedule(List.of(Duration.ofMinutes(1))));
        leg.retryRequested(NOW.plusSeconds(60));
        leg.markFailed("X", NOW, new RetrySchedule(List.of(Duration.ofMinutes(1))));

        assertThat(leg.markCompleted(UUID.randomUUID().toString(), NOW)).isTrue();
        assertThat(leg.isCompleted()).isTrue();
    }

    @Test
    void retryNotDue_isRefused() {
        SettlementLeg leg = leg();

        assertThatThrownBy(() -> leg.retryRequested(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void result_mustNameTheCommandExactly() {
        SettlementLeg leg = leg();
        Money amount = new Money(new BigDecimal("1000.00"));

        assertThat(leg.matchesResult(leg.getUserId(), "RELEASE_TO_SELLER", "MILESTONE_PAYMENT", amount)).isTrue();
        assertThat(leg.matchesResult(leg.getUserId(), "REFUND_TO_BUYER", "MILESTONE_PAYMENT", amount)).isFalse();
        assertThat(leg.matchesResult(leg.getUserId(), "RELEASE_TO_SELLER", "SELLER_DEPOSIT", amount)).isFalse();
        assertThat(leg.matchesResult(UUID.randomUUID().toString(), "RELEASE_TO_SELLER", "MILESTONE_PAYMENT",
                amount)).isFalse();
        assertThat(leg.matchesResult(leg.getUserId(), "RELEASE_TO_SELLER", "MILESTONE_PAYMENT",
                new Money(new BigDecimal("999.00")))).isFalse();
    }

    static SettlementLeg leg() {
        return SettlementLeg.requestMilestonePayment(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), SettlementLegType.RELEASE_TO_SELLER, UUID.randomUUID().toString(),
                new Money(new BigDecimal("1000.00")), NOW);
    }
}
