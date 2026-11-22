package com.agricontract.escrow.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.escrow.domain.exception.InvalidRemedyDecisionException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pool math of #249: ledger locks minus every other entry minus what is in flight. */
class FundConservationTest {

    static final String BUYER = UUID.randomUUID().toString();
    static final String SELLER = UUID.randomUUID().toString();
    static final String M = UUID.randomUUID().toString();

    static final List<LedgerEntryView> DEPOSITS = List.of(
            entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
            entry("LOCK_SELLER_DEPOSIT", "SELLER_DEPOSIT", null, "5000000.00"));

    @Test
    void normalCompletion_returnsEveryDeposit_accepted() {
        assertThatCode(() -> FundConservation.check(List.of(
                planned("PAYMENT_REFUND", "REFUND_TO_BUYER", "BUYER_DEPOSIT", null, BUYER, "10000000.00"),
                planned("PAYMENT_SETTLEMENT", "RELEASE_TO_SELLER", "SELLER_DEPOSIT", null, SELLER, "5000000.00")),
                DEPOSITS, List.of())).doesNotThrowAnyException();
    }

    @Test
    void depositLeg_overTheLock_isRejected() {
        assertThatThrownBy(() -> FundConservation.check(List.of(
                planned("PAYMENT_REFUND", "REFUND_TO_BUYER", "BUYER_DEPOSIT", null, BUYER, "10000000.01")),
                DEPOSITS, List.of())).isInstanceOf(InvalidRemedyDecisionException.class);
    }

    @Test
    void depositLeg_underTheLock_isAccepted() {
        assertThatCode(() -> FundConservation.check(List.of(
                planned("DEPOSIT_FORFEITURE", "DEPOSIT_FORFEITURE", "BUYER_DEPOSIT", null, SELLER, "4000000.00")),
                DEPOSITS, List.of())).doesNotThrowAnyException();
    }

    @Test
    void milestoneBatch_movedWhole_isAccepted() {
        assertThatCode(() -> FundConservation.check(milestoneLegs("487500000.00", "12500000.00"), lockM(), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void milestoneBatch_notMovedWhole_isRejected() {
        assertThatThrownBy(() -> FundConservation.check(milestoneLegs("487500000.00", "12499999.00"), lockM(),
                List.of())).isInstanceOf(InvalidRemedyDecisionException.class).hasMessageContaining("whole");
    }

    @Test
    void settlementLegStillInFlight_reducesWhatIsAvailable() {
        List<PoolAmount> inFlight = List.of(new PoolAmount(FundPool.of("MILESTONE_PAYMENT", M), new BigDecimal("100.00")));

        assertThatThrownBy(() -> FundConservation.check(milestoneLegs("487500000.00", "12500000.00"), lockM(),
                inFlight)).isInstanceOf(InvalidRemedyDecisionException.class);
        assertThatCode(() -> FundConservation.check(milestoneLegs("487500000.00", "12499900.00"), lockM(), inFlight))
                .doesNotThrowAnyException();
    }

    @Test
    void completedEntriesInTheLedger_reduceWhatIsAvailable() {
        List<LedgerEntryView> ledger = List.of(
                entry("LOCK_BUYER_DEPOSIT", "BUYER_DEPOSIT", null, "10000000.00"),
                entry("REFUND_TO_BUYER", "BUYER_DEPOSIT", null, "10000000.00"));

        assertThatThrownBy(() -> FundConservation.check(List.of(
                planned("PAYMENT_REFUND", "REFUND_TO_BUYER", "BUYER_DEPOSIT", null, BUYER, "1.00")), ledger,
                List.of())).isInstanceOf(InvalidRemedyDecisionException.class);
    }

    @Test
    void depositsAndMilestonesAreSeparatePools() {
        // The milestone batch cannot be paid from the deposit pool, nor the reverse.
        assertThatThrownBy(() -> FundConservation.check(List.of(
                planned("PAYMENT_REFUND", "REFUND_TO_BUYER", "MILESTONE_PAYMENT", M, BUYER, "10000000.00")),
                DEPOSITS, List.of())).isInstanceOf(InvalidRemedyDecisionException.class);
    }

    private static List<PlannedRemedyLeg> milestoneLegs(String release, String refund) {
        return List.of(
                planned("PAYMENT_SETTLEMENT", "RELEASE_TO_SELLER", "MILESTONE_PAYMENT", M, SELLER, release),
                planned("PAYMENT_REFUND", "REFUND_TO_BUYER", "MILESTONE_PAYMENT", M, BUYER, refund));
    }

    private static List<LedgerEntryView> lockM() {
        return List.of(entry("LOCK_MILESTONE", "MILESTONE_PAYMENT", M, "500000000.00"));
    }

    private static LedgerEntryView entry(String type, String fund, String milestone, String amount) {
        return new LedgerEntryView(type, fund, milestone, new BigDecimal(amount));
    }

    private static PlannedRemedyLeg planned(String remedyType, String entryType, String fund, String milestone,
                                            String user, String amount) {
        return new PlannedRemedyLeg(UUID.randomUUID().toString(), RemedyType.valueOf(remedyType),
                RemedyEntryType.valueOf(entryType), fund, user, milestone, new BigDecimal(amount));
    }
}
