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

/** Clean-path settlement of a locked milestone (#248): legs, conservation, repeats, SETTLED only on bank results. */
class EscrowMilestoneSettlementTest {

    static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    static final String CONTRACT = UUID.randomUUID().toString();
    static final String BUYER = UUID.randomUUID().toString();
    static final String SELLER = UUID.randomUUID().toString();
    static final String EVENT = UUID.randomUUID().toString();
    static final RetrySchedule SCHEDULE = new RetrySchedule(List.of(Duration.ofMinutes(5)));

    @Test
    void partialDelivery_releasesActualAndRefundsTheRest_addingUpToTheLock() {
        EscrowMilestone milestone = locked("300000000.00");

        List<SettlementLeg> legs = milestone.settle(CONTRACT, money("300000000.00"), new BigDecimal("292500000.00"),
                SELLER, EVENT, NOW);

        assertThat(legs).extracting(SettlementLeg::getType)
                .containsExactly(SettlementLegType.RELEASE_TO_SELLER, SettlementLegType.REFUND_TO_BUYER);
        SettlementLeg release = legs.get(0);
        SettlementLeg refund = legs.get(1);
        assertThat(release.getUserId()).isEqualTo(SELLER);
        assertThat(release.getAmount()).isEqualTo(money("292500000.00"));
        assertThat(refund.getUserId()).isEqualTo(BUYER);
        assertThat(refund.getAmount()).isEqualTo(money("7500000.00"));
        assertThat(release.getAmount().amount().add(refund.getAmount().amount()))
                .isEqualByComparingTo(milestone.getBatchAmount().amount());
        assertThat(legs).allSatisfy(leg -> {
            assertThat(leg.getStatus()).isEqualTo(SettlementLegStatus.REQUESTED);
            assertThat(leg.getFundType()).isEqualTo("MILESTONE_PAYMENT");
            assertThat(leg.getMilestoneId()).isEqualTo(milestone.getMilestoneId());
            assertThat(leg.getTriggerEventId()).isEqualTo(EVENT);
        });
        assertThat(release.getRequestEventId()).isNotEqualTo(refund.getRequestEventId());
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.SETTLING);
        assertThat(milestone.getSettlementEventId()).isEqualTo(EVENT);
    }

    @Test
    void fullDelivery_omitsTheZeroRefund() {
        EscrowMilestone milestone = locked("300000000.00");

        List<SettlementLeg> legs = milestone.settle(CONTRACT, money("300000000.00"), new BigDecimal("300000000.00"),
                SELLER, EVENT, NOW);

        assertThat(legs).singleElement().satisfies(leg -> {
            assertThat(leg.getType()).isEqualTo(SettlementLegType.RELEASE_TO_SELLER);
            assertThat(leg.getAmount()).isEqualTo(money("300000000.00"));
        });
    }

    @Test
    void nothingDelivered_omitsTheZeroRelease_andNeedsNoSeller() {
        EscrowMilestone milestone = locked("300000000.00");

        List<SettlementLeg> legs = milestone.settle(CONTRACT, money("300000000.00"), new BigDecimal("0.00"), null,
                EVENT, NOW);

        assertThat(legs).singleElement().satisfies(leg -> {
            assertThat(leg.getType()).isEqualTo(SettlementLegType.REFUND_TO_BUYER);
            assertThat(leg.getUserId()).isEqualTo(BUYER);
            assertThat(leg.getAmount()).isEqualTo(money("300000000.00"));
        });
    }

    @Test
    void actualAboveTheLock_isRefused_andNothingChanges() {
        EscrowMilestone milestone = locked("300000000.00");

        assertThatThrownBy(() -> milestone.settle(CONTRACT, money("300000000.00"), new BigDecimal("300000001.00"),
                SELLER, EVENT, NOW)).isInstanceOf(InvalidDepositLegException.class);
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.LOCKED);
    }

    @Test
    void lockedAmountOtherThanTheBatch_isRefused() {
        EscrowMilestone milestone = locked("300000000.00");

        assertThatThrownBy(() -> milestone.settle(CONTRACT, money("310000000.00"), new BigDecimal("300000000.00"),
                SELLER, EVENT, NOW)).isInstanceOf(InvalidDepositLegException.class);
    }

    @Test
    void negativeActual_orOtherContract_orNoSellerToPay_isRefused() {
        assertThatThrownBy(() -> locked("1000.00").settle(CONTRACT, money("1000.00"), new BigDecimal("-1.00"),
                SELLER, EVENT, NOW)).isInstanceOf(InvalidDepositLegException.class);
        assertThatThrownBy(() -> locked("1000.00").settle(UUID.randomUUID().toString(), money("1000.00"),
                new BigDecimal("1000.00"), SELLER, EVENT, NOW)).isInstanceOf(InvalidDepositLegException.class);
        assertThatThrownBy(() -> locked("1000.00").settle(CONTRACT, money("1000.00"), new BigDecimal("500.00"),
                null, EVENT, NOW)).isInstanceOf(InvalidDepositLegException.class);
    }

    @Test
    void unlockedMilestone_cannotSettle() {
        EscrowMilestone pending = EscrowMilestone.requestFunding(UUID.randomUUID().toString(), CONTRACT, 1, BUYER,
                money("1000.00"), NOW, UUID.randomUUID().toString(), NOW);

        assertThatThrownBy(() -> pending.settle(CONTRACT, money("1000.00"), new BigDecimal("1000.00"), SELLER, EVENT,
                NOW)).isInstanceOf(InvalidDepositLegException.class);
    }

    @Test
    void repeat_isTheSameSettlementOnlyWithTheSameAmountsAndSeller() {
        EscrowMilestone milestone = locked("300000000.00");
        List<SettlementLeg> legs = milestone.settle(CONTRACT, money("300000000.00"), new BigDecimal("292500000.00"),
                SELLER, EVENT, NOW);

        assertThat(milestone.isSettlementStarted()).isTrue();
        assertThat(milestone.isSameSettlement(legs, CONTRACT, money("300000000.00"),
                new BigDecimal("292500000.00"), SELLER)).isTrue();
        assertThat(milestone.isSameSettlement(legs, CONTRACT, money("300000000.00"),
                new BigDecimal("300000000.00"), SELLER)).isFalse();
        assertThat(milestone.isSameSettlement(legs, CONTRACT, money("300000000.00"),
                new BigDecimal("292500000.00"), UUID.randomUUID().toString())).isFalse();
        assertThat(milestone.isSameSettlement(legs, CONTRACT, money("310000000.00"),
                new BigDecimal("292500000.00"), SELLER)).isFalse();
    }

    @Test
    void settled_onlyWhenTheBankConfirmedEveryLeg() {
        EscrowMilestone milestone = locked("300000000.00");
        List<SettlementLeg> legs = milestone.settle(CONTRACT, money("300000000.00"), new BigDecimal("292500000.00"),
                SELLER, EVENT, NOW);

        assertThat(milestone.markSettledIfComplete(legs, NOW)).isFalse();
        legs.get(0).markCompleted(UUID.randomUUID().toString(), NOW);
        assertThat(milestone.markSettledIfComplete(legs, NOW)).isFalse();
        legs.get(1).markFailed("KILL_SWITCH", NOW, SCHEDULE);
        assertThat(milestone.markSettledIfComplete(legs, NOW)).isFalse();
        legs.get(1).markCompleted(UUID.randomUUID().toString(), NOW);

        assertThat(milestone.markSettledIfComplete(legs, NOW)).isTrue();
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.SETTLED);
        assertThat(milestone.getSettledAt()).isEqualTo(NOW);
        assertThat(milestone.markSettledIfComplete(legs, NOW)).as("only once").isFalse();
    }

    @Test
    void lateLockResult_afterSettlementStarted_neverReopensTheLock() {
        EscrowMilestone milestone = locked("1000.00");
        milestone.settle(CONTRACT, money("1000.00"), new BigDecimal("1000.00"), SELLER, EVENT, NOW);

        assertThat(milestone.markLocked(UUID.randomUUID().toString(), NOW, NOW))
                .isEqualTo(EscrowMilestone.LockOutcome.DUPLICATE);
        assertThat(milestone.markFailed("LATE", NOW, SCHEDULE))
                .isEqualTo(EscrowMilestone.FailureOutcome.IGNORED_ALREADY_LOCKED);
        assertThat(milestone.getStatus()).isEqualTo(EscrowMilestoneStatus.SETTLING);
    }

    static EscrowMilestone locked(String batchAmount) {
        EscrowMilestone milestone = EscrowMilestone.requestFunding(UUID.randomUUID().toString(), CONTRACT, 1, BUYER,
                money(batchAmount), NOW.minusSeconds(60), UUID.randomUUID().toString(), NOW.minusSeconds(60));
        milestone.markLocked(UUID.randomUUID().toString(), NOW.minusSeconds(30), NOW.minusSeconds(30));
        return milestone;
    }

    static Money money(String amount) {
        return new Money(new BigDecimal(amount));
    }
}
