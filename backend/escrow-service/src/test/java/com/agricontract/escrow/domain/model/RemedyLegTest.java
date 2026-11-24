package com.agricontract.escrow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RemedyLeg.restartRetryCycle (#609, D93): only a FAILED leg restarts; SENT and COMPLETED are a no-op returning false
 * (chosen over throwing: OPERATOR may press retry while a leg is in flight or done, and the use case skips those).
 */
class RemedyLegTest {

    static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");
    static final RetrySchedule ONE_RETRY = new RetrySchedule(List.of(Duration.ofMinutes(5)));

    @Test
    void exhaustedLeg_restartsAsSent_withAttemptsReset_reasonKept_sameCommand() {
        RemedyLeg leg = leg(RemedyEntryType.DEPOSIT_FORFEITURE, "SELLER_DEPOSIT", "BUYER");
        String request = leg.getRequestEventId();
        leg.markFailed("KILL_SWITCH_ACTIVE", T0, ONE_RETRY);
        leg.retryRequested(T0.plus(Duration.ofMinutes(5)));
        assertThat(leg.markFailed("KILL_SWITCH_ACTIVE", T0.plusSeconds(400), ONE_RETRY))
                .isEqualTo(RemedyLeg.FailureOutcome.RETRIES_EXHAUSTED);

        Instant now = T0.plusSeconds(3_600);
        assertThat(leg.restartRetryCycle(now)).isTrue();

        assertThat(leg.getStatus()).isEqualTo(RemedyLegStatus.SENT);
        assertThat(leg.getAttempts()).isZero();
        assertThat(leg.getNextAttemptAt()).isNull();
        assertThat(leg.getFailureReason()).isEqualTo("KILL_SWITCH_ACTIVE");
        assertThat(leg.getRequestEventId()).isEqualTo(request);
        assertThat(leg.getUpdatedAt()).isEqualTo(now);
        // A new full cycle: the next refusal schedules a resend again.
        assertThat(leg.markFailed("BANK_ERROR", now, ONE_RETRY)).isEqualTo(RemedyLeg.FailureOutcome.RETRY_SCHEDULED);
    }

    @Test
    void legWithARetryStillScheduled_restartsToo() {
        RemedyLeg leg = leg(RemedyEntryType.REFUND_TO_BUYER, "BUYER_DEPOSIT", "BUYER");
        leg.markFailed("BANK_ERROR", T0, ONE_RETRY);

        assertThat(leg.restartRetryCycle(T0.plusSeconds(1))).isTrue();
        assertThat(leg.getStatus()).isEqualTo(RemedyLegStatus.SENT);
        assertThat(leg.getNextAttemptAt()).isNull();
    }

    @Test
    void sentAndCompletedLegs_areNotRestarted() {
        RemedyLeg sent = leg(RemedyEntryType.REFUND_TO_BUYER, "BUYER_DEPOSIT", "BUYER");
        assertThat(sent.restartRetryCycle(T0)).isFalse();
        assertThat(sent.getStatus()).isEqualTo(RemedyLegStatus.SENT);

        RemedyLeg completed = leg(RemedyEntryType.REFUND_TO_BUYER, "BUYER_DEPOSIT", "BUYER");
        completed.markCompleted(UUID.randomUUID().toString(), T0);
        assertThat(completed.restartRetryCycle(T0.plusSeconds(1))).isFalse();
        assertThat(completed.getStatus()).isEqualTo(RemedyLegStatus.COMPLETED);
        assertThat(completed.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void payeeParty_isTheStoredRole_orDerivedForAnOldRow_whenOnlyOnePayeeIsPossible() {
        assertThat(leg(RemedyEntryType.CONTRACTUAL_PENALTY, "MILESTONE_PAYMENT", "SELLER").payeeParty())
                .contains("SELLER");
        assertThat(leg(RemedyEntryType.RELEASE_TO_SELLER, "SELLER_DEPOSIT", null).payeeParty()).contains("SELLER");
        assertThat(leg(RemedyEntryType.REFUND_TO_BUYER, "MILESTONE_PAYMENT", null).payeeParty()).contains("BUYER");
        assertThat(leg(RemedyEntryType.DEPOSIT_FORFEITURE, "SELLER_DEPOSIT", null).payeeParty()).contains("BUYER");
        assertThat(leg(RemedyEntryType.DEPOSIT_FORFEITURE, "BUYER_DEPOSIT", null).payeeParty()).contains("SELLER");
        assertThat(leg(RemedyEntryType.CONTRACTUAL_PENALTY, "MILESTONE_PAYMENT", null).payeeParty()).isEmpty();
        assertThat(leg(RemedyEntryType.DAMAGES_COMPENSATION, "MILESTONE_PAYMENT", null).payeeParty()).isEmpty();
    }

    private static RemedyLeg leg(RemedyEntryType entryType, String fundType, String payeeRole) {
        return RemedyLeg.reconstitute(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), null, RemedyType.DEPOSIT_FORFEITURE, entryType, fundType,
                UUID.randomUUID().toString(), payeeRole, new Money(new BigDecimal("100.00")),
                UUID.randomUUID().toString(), RemedyLegStatus.SENT, 0, null, null, null, T0, T0);
    }
}
