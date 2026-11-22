package com.agricontract.escrow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.escrow.domain.exception.InvalidRemedyDecisionException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The leg to bank command table of #249 (D86: role receives the money). */
class RemedyLegPlannerTest {

    static final String BUYER = UUID.randomUUID().toString();
    static final String SELLER = UUID.randomUUID().toString();
    static final String M = UUID.randomUUID().toString();

    @ParameterizedTest
    @CsvSource({
            "PAYMENT_SETTLEMENT, MILESTONE_PAYMENT, SELLER, RELEASE_TO_SELLER, seller",
            "PAYMENT_SETTLEMENT, SELLER_DEPOSIT, SELLER, RELEASE_TO_SELLER, seller",
            "PAYMENT_REFUND, MILESTONE_PAYMENT, BUYER, REFUND_TO_BUYER, buyer",
            "PAYMENT_REFUND, BUYER_DEPOSIT, BUYER, REFUND_TO_BUYER, buyer",
            "DEPOSIT_FORFEITURE, BUYER_DEPOSIT, SELLER, DEPOSIT_FORFEITURE, seller",
            "DEPOSIT_FORFEITURE, SELLER_DEPOSIT, BUYER, DEPOSIT_FORFEITURE, buyer",
            "CONTRACTUAL_PENALTY, MILESTONE_PAYMENT, BUYER, CONTRACTUAL_PENALTY, buyer",
            "CONTRACTUAL_PENALTY, MILESTONE_PAYMENT, SELLER, CONTRACTUAL_PENALTY, seller",
            "DAMAGES_COMPENSATION, MILESTONE_PAYMENT, BUYER, DAMAGES_COMPENSATION, buyer",
            "DAMAGES_COMPENSATION, MILESTONE_PAYMENT, SELLER, DAMAGES_COMPENSATION, seller"})
    void validRow_mapsToItsCommand_paidToTheRecipient(String type, String fund, String role, String entry,
                                                      String payee) {
        List<PlannedRemedyLeg> planned = RemedyLegPlanner.plan(BUYER, SELLER, List.of(M),
                List.of(leg(type, fund, role, "10.00")));

        assertThat(planned).hasSize(1);
        assertThat(planned.get(0).entryType().name()).isEqualTo(entry);
        assertThat(planned.get(0).userId()).isEqualTo(payee.equals("buyer") ? BUYER : SELLER);
        assertThat(planned.get(0).milestoneId()).isEqualTo(fund.equals("MILESTONE_PAYMENT") ? M : null);
    }

    @ParameterizedTest
    @CsvSource({
            "PAYMENT_SETTLEMENT, MILESTONE_PAYMENT, BUYER",
            "PAYMENT_SETTLEMENT, BUYER_DEPOSIT, SELLER",
            "PAYMENT_REFUND, MILESTONE_PAYMENT, SELLER",
            "PAYMENT_REFUND, SELLER_DEPOSIT, BUYER",
            "DEPOSIT_FORFEITURE, BUYER_DEPOSIT, BUYER",
            "DEPOSIT_FORFEITURE, SELLER_DEPOSIT, SELLER",
            "DEPOSIT_FORFEITURE, MILESTONE_PAYMENT, SELLER",
            "CONTRACTUAL_PENALTY, BUYER_DEPOSIT, SELLER",
            "CONTRACTUAL_PENALTY, SELLER_DEPOSIT, BUYER",
            "DAMAGES_COMPENSATION, BUYER_DEPOSIT, BUYER"})
    void invalidCombination_invalidatesTheWholeDecision(String type, String fund, String role) {
        List<RemedyLegInput> legs = List.of(
                leg("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER", "5.00"), leg(type, fund, role, "10.00"));

        assertThatThrownBy(() -> RemedyLegPlanner.plan(BUYER, SELLER, List.of(M), legs))
                .isInstanceOf(InvalidRemedyDecisionException.class);
    }

    @Test
    void invalidCombination_isInvalidEvenWithAZeroAmount() {
        assertThatThrownBy(() -> RemedyLegPlanner.plan(BUYER, SELLER, List.of(M),
                List.of(leg("PAYMENT_REFUND", "MILESTONE_PAYMENT", "SELLER", "0.00"))))
                .isInstanceOf(InvalidRemedyDecisionException.class);
    }

    @Test
    void noneAndZeroLegs_areSkipped() {
        List<PlannedRemedyLeg> planned = RemedyLegPlanner.plan(BUYER, SELLER, List.of(M), List.of(
                leg("NONE", "BUYER_DEPOSIT", "SELLER", "99.00"),
                leg("PAYMENT_REFUND", "MILESTONE_PAYMENT", "BUYER", "0.00"),
                leg("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER", "7.00")));

        assertThat(planned).hasSize(1);
        assertThat(planned.get(0).amount()).isEqualByComparingTo("7.00");
    }

    @Test
    void milestonePaymentLegs_needExactlyOneAffectedMilestone() {
        List<RemedyLegInput> legs = List.of(leg("PAYMENT_SETTLEMENT", "MILESTONE_PAYMENT", "SELLER", "7.00"));

        assertThatThrownBy(() -> RemedyLegPlanner.plan(BUYER, SELLER, List.of(), legs))
                .isInstanceOf(InvalidRemedyDecisionException.class);
        assertThatThrownBy(() -> RemedyLegPlanner.plan(BUYER, SELLER, List.of(M, UUID.randomUUID().toString()), legs))
                .isInstanceOf(InvalidRemedyDecisionException.class);
    }

    @Test
    void depositLegs_doNotNeedAMilestone_andCarryNone() {
        List<PlannedRemedyLeg> planned = RemedyLegPlanner.plan(BUYER, SELLER, List.of(), List.of(
                leg("PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10.00")));

        assertThat(planned.get(0).milestoneId()).isNull();
    }

    @Test
    void repeatedRemedyLegId_invalidatesTheDecision() {
        RemedyLegInput one = leg("PAYMENT_REFUND", "BUYER_DEPOSIT", "BUYER", "10.00");

        assertThatThrownBy(() -> RemedyLegPlanner.plan(BUYER, SELLER, List.of(), List.of(one, one)))
                .isInstanceOf(InvalidRemedyDecisionException.class);
    }

    static RemedyLegInput leg(String type, String fund, String role, String amount) {
        return new RemedyLegInput(UUID.randomUUID().toString(), type, fund, role, new BigDecimal(amount));
    }
}
