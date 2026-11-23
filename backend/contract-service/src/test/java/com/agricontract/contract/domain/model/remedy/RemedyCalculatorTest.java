package com.agricontract.contract.domain.model.remedy;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.input;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.template;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseComputedResolution;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.domain.model.breach.BreachReasonCode;
import com.agricontract.contract.domain.model.breach.BreachRequester;
import com.agricontract.contract.domain.model.breach.BreachSeverity;
import com.agricontract.contract.domain.model.breach.ContestStatus;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.ContestedBy;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.FundingStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneFlag;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.terms.ContractTermsInput;
import com.agricontract.contract.domain.model.terms.ContractTermsSnapshot;
import com.agricontract.contract.domain.model.terms.DamagesPolicy;
import com.agricontract.contract.domain.model.terms.GoverningLaw;
import com.agricontract.contract.domain.model.terms.LegalProfile;
import com.agricontract.contract.domain.model.terms.MilestoneTerm;
import com.agricontract.contract.domain.model.terms.VndAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** #137: the RemedyDecision of a resolved milestone dispute (P 15,000, committed 10,000 kg, locked 150,000,000). */
class RemedyCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");
    private static final String CONTRACT = UUID.randomUUID().toString();
    private static final String MILESTONE = UUID.randomUUID().toString();
    private static final String CASE = UUID.randomUUID().toString();

    private final RemedyCalculator calculator = new RemedyCalculator(() -> UUID.randomUUID().toString());

    // ---- no fault: PARTIALLY, QUANTITY, CONFORMING ----

    @Test
    void partiallyConforming_paysTheSellerAndRefundsTheBuyer_finalizedAtOnce() {
        RemedyDecision d = calculator.calculate(resolved(computed(FlagIssueType.QUALITY,
                QualityDisposition.PARTIALLY_CONFORMING, null, "144060000.00", "5940000.00", SignerRole.BUYER)),
                milestone("10000"), terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 1, NOW);

        assertThat(d.status()).isEqualTo(RemedyStatus.FINALIZED);
        assertThat(d.finalizedAt()).isEqualTo(NOW);
        assertThat(d.finalBreachingRole()).isNull();
        assertThat(d.breachReasonCode()).isNull();
        assertThat(d.qualityDisposition()).isEqualTo(QualityDisposition.PARTIALLY_CONFORMING);
        assertThat(d.penaltyEligible()).isFalse();
        assertThat(d.reputationEligible()).isFalse();
        assertThat(d.penaltyAmount().wire()).isEqualTo("0.00");
        assertThat(d.penaltyDebtAmount().wire()).isEqualTo("0.00");
        assertThat(d.settledMilestoneCount()).isEqualTo(1);
        assertThat(d.breachCaseId()).isEqualTo(CASE);
        assertThat(d.milestoneId()).isEqualTo(MILESTONE);
        assertThat(d.decisionSource()).isEqualTo(DecisionSource.INSPECTOR_L1_5);
        assertThat(d.legs()).extracting(RemedyLeg::remedyType, RemedyLeg::fundType, RemedyLeg::role,
                l -> l.amount().wire()).containsExactly(
                org.assertj.core.groups.Tuple.tuple(RemedyType.PAYMENT_SETTLEMENT, RemedyFundType.MILESTONE_PAYMENT,
                        SignerRole.SELLER, "144060000.00"),
                org.assertj.core.groups.Tuple.tuple(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT,
                        SignerRole.BUYER, "5940000.00"));
        assertThat(d.sellerPayment().wire()).isEqualTo("144060000.00");
        assertThat(d.buyerRefund().wire()).isEqualTo("5940000.00");
        assertMilestoneLegsAddUp(d);
    }

    @Test
    void quantityConforming_followsTheComputedMoney_andCarriesNoDisposition() {
        RemedyDecision d = calculator.calculate(resolved(computed(FlagIssueType.QUANTITY, null, null, "147000000.00",
                "3000000.00", SignerRole.SELLER)), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, NOW);

        assertThat(d.status()).isEqualTo(RemedyStatus.FINALIZED);
        assertThat(d.qualityDisposition()).isNull();
        assertThat(d.legs()).extracting(l -> l.amount().wire()).containsExactly("147000000.00", "3000000.00");
        assertMilestoneLegsAddUp(d);
    }

    @Test
    void aZeroAmountIsNeverALeg() {
        RemedyDecision allToSeller = calculator.calculate(resolved(computed(FlagIssueType.QUALITY,
                QualityDisposition.CONFORMING, null, "150000000.00", "0.00", SignerRole.SELLER)), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("0.00"), 0, NOW);
        RemedyDecision allToBuyer = calculator.calculate(resolved(computed(FlagIssueType.QUANTITY, null, null,
                "0.00", "150000000.00", SignerRole.BUYER)), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("0.00"), 0, NOW);

        assertThat(allToSeller.legs()).extracting(RemedyLeg::remedyType).containsExactly(RemedyType.PAYMENT_SETTLEMENT);
        assertThat(allToBuyer.legs()).extracting(RemedyLeg::remedyType).containsExactly(RemedyType.PAYMENT_REFUND);
    }

    // ---- fault: NON_CONFORMING ----

    @Test
    void nonConforming_refundsTheWholeMilestone_andForfeitsThePenaltyFromTheSellerDeposit() {
        RemedyDecision d = calculator.calculate(resolvedBySeller(), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, NOW);

        assertThat(d.status()).isEqualTo(RemedyStatus.PENDING_CONTEST);
        assertThat(d.finalizedAt()).isNull();
        assertThat(d.finalBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertThat(d.breachReasonCode()).isEqualTo(BreachReasonCode.QUALITY_BELOW_COMMITTED);
        assertThat(d.penaltyEligible()).isTrue();
        assertThat(d.reputationEligible()).isTrue();
        assertThat(d.penaltyAmount().wire()).isEqualTo("12000000.00");
        assertThat(d.penaltyDebtAmount().wire()).isEqualTo("0.00");
        assertThat(d.legs()).extracting(RemedyLeg::remedyType, RemedyLeg::fundType, RemedyLeg::role,
                l -> l.amount().wire()).containsExactly(
                org.assertj.core.groups.Tuple.tuple(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT,
                        SignerRole.BUYER, "150000000.00"),
                org.assertj.core.groups.Tuple.tuple(RemedyType.DEPOSIT_FORFEITURE, RemedyFundType.SELLER_DEPOSIT,
                        SignerRole.BUYER, "12000000.00"));
        assertThat(d.sellerDepositForfeited().wire()).isEqualTo("12000000.00");
        assertThat(d.legs()).extracting(RemedyLeg::remedyType)
                .doesNotContain(RemedyType.DAMAGES_COMPENSATION, RemedyType.CONTRACTUAL_PENALTY);
        assertMilestoneLegsAddUp(d);
    }

    @Test
    void nonConforming_aSecondDecisionIsCappedByWhatIsLeftOfTheDeposit_theRestIsDebt() {
        RemedyDecision d = calculator.calculate(resolvedBySeller(), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("10500000.00"), 1, NOW);

        assertThat(d.penaltyAmount().wire()).isEqualTo("12000000.00");
        assertThat(d.sellerDepositForfeited().wire()).isEqualTo("10500000.00");
        assertThat(d.penaltyDebtAmount().wire()).isEqualTo("1500000.00");
        assertThat(d.settledMilestoneCount()).isEqualTo(1);
    }

    @Test
    void nonConforming_withNoSellerDeposit_hasNoForfeitureLeg_andTheWholePenaltyIsDebt() {
        RemedyDecision d = calculator.calculate(resolvedBySeller(), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("0.00"), 0, NOW);

        assertThat(d.legs()).extracting(RemedyLeg::remedyType).containsExactly(RemedyType.PAYMENT_REFUND);
        assertThat(d.sellerDepositForfeited().wire()).isEqualTo("0.00");
        assertThat(d.penaltyAmount().wire()).isEqualTo("12000000.00");
        assertThat(d.penaltyDebtAmount().wire()).isEqualTo("12000000.00");
    }

    @Test
    void penaltyBase_isTheMilestoneNotTheContract_roundedOnceToTheDong() {
        // 0.08 x 15,000 x 3,333.333 = 3,999,999.6 -> 4,000,000.
        RemedyDecision d = calculator.calculate(resolvedBySeller(), milestone("3333.333"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, NOW);

        assertThat(d.penaltyAmount().wire()).isEqualTo("4000000.00");
    }

    @Test
    void penaltyRateAboveTheLegalCap_isRefused_butTheCivilCodeHasNoPercentageCap() {
        assertThatThrownBy(() -> calculator.calculate(resolvedBySeller(), milestone("10000"),
                terms("0.09", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("legal cap");

        RemedyDecision civil = calculator.calculate(resolvedBySeller(), milestone("10000"),
                terms("0.09", GoverningLaw.VN_CIVIL_LAW), deposit("22500000.00"), 0, NOW);
        assertThat(civil.penaltyAmount().wire()).isEqualTo("13500000.00");
    }

    // ---- invariants ----

    @Test
    void everyLegHasItsOwnId() {
        RemedyDecision d = calculator.calculate(resolvedBySeller(), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, NOW);

        List<String> ids = new ArrayList<>(d.legs().stream().map(RemedyLeg::remedyLegId).toList());
        ids.add(d.remedyDecisionId());
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
    }

    @Test
    void aComputedResolutionThatDoesNotAddUpToTheLockedBatch_isRefused() {
        assertThatThrownBy(() -> calculator.calculate(resolved(computed(FlagIssueType.QUANTITY, null, null,
                "100000000.00", "3000000.00", SignerRole.SELLER)), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("0.00"), 0, NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not add up");
    }

    @Test
    void aCaseThatIsNotResolved_orHasNoComputedResolution_orIsAnotherMilestones_isRefused() {
        BreachCase underReview = resolved(computed(FlagIssueType.QUANTITY, null, null, "147000000.00",
                "3000000.00", SignerRole.SELLER));
        BreachCase notResolved = new BreachCase(CASE, CONTRACT, MILESTONE, BreachRequester.BUYER, SignerRole.SELLER,
                BreachReasonCode.QUALITY_BELOW_COMMITTED, BreachSeverity.MINOR, BreachCaseStatus.UNDER_REVIEW,
                List.of(), NOW, null, null, List.of(), null, null, ContestStatus.NOT_APPLICABLE, null,
                underReview.computedResolution(), null, List.of(), null, null);
        BreachCase noComputed = new BreachCase(CASE, CONTRACT, MILESTONE, BreachRequester.BUYER, SignerRole.SELLER,
                BreachReasonCode.QUALITY_BELOW_COMMITTED, BreachSeverity.MINOR, BreachCaseStatus.RESOLVED, List.of(),
                NOW, null, DecisionSource.ARBITRATOR, List.of(), NOW, "a", ContestStatus.NOT_APPLICABLE, null, null, null,
                List.of(), null, null);
        var terms = terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW);

        assertThatThrownBy(() -> calculator.calculate(notResolved, milestone("10000"), terms, deposit("0.00"), 0, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calculator.calculate(noComputed, milestone("10000"), terms, deposit("0.00"), 0, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calculator.calculate(underReview,
                milestone("10000", UUID.randomUUID().toString()), terms, deposit("0.00"), 0, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finalizing_movesOnlyTheStatus_once() {
        RemedyDecision pending = calculator.calculate(resolvedBySeller(), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, NOW);
        Instant later = NOW.plusSeconds(3600);

        RemedyDecision finalized = pending.finalizedAt(later);

        assertThat(finalized.status()).isEqualTo(RemedyStatus.FINALIZED);
        assertThat(finalized.finalizedAt()).isEqualTo(later);
        assertThat(finalized.createdAt()).isEqualTo(NOW);
        assertThat(finalized.legs()).isEqualTo(pending.legs());
        assertThat(finalized.penaltyAmount()).isEqualTo(pending.penaltyAmount());
        assertThatThrownBy(() -> finalized.finalizedAt(later)).isInstanceOf(IllegalStateException.class);
    }

    // ---- fixtures ----

    private static void assertMilestoneLegsAddUp(RemedyDecision d) {
        BigDecimal sum = d.legs().stream().filter(l -> l.fundType() == RemedyFundType.MILESTONE_PAYMENT)
                .map(l -> l.amount().value()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("150000000");
    }

    private static VndAmount deposit(String wire) {
        return VndAmount.parse(wire);
    }

    private static BreachCaseComputedResolution computed(FlagIssueType type, QualityDisposition disposition,
                                                         SignerRole finalRole, String seller, String refund,
                                                         SignerRole prevailing) {
        return new BreachCaseComputedResolution(UUID.randomUUID().toString(), type, new BigDecimal("9800"),
                disposition, finalRole, VndAmount.parse(seller), VndAmount.parse(refund), prevailing);
    }

    // ---- D92: agreed exits and the inspection fee party ----

    @Test
    void aReportBasedDecision_putsTheInspectionFeeOnTheNonPrevailingRole() {
        RemedyDecision d = calculator.calculate(resolvedBySeller(), milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, NOW);

        assertThat(d.inspectionFeeParty()).isEqualTo(InspectionFeeParty.SELLER);
    }

    @Test
    void anAgreedConcessionWithFault_isFinalizedAtOnce_withThePenalty_andTheGivenFeeParty() {
        BreachCaseComputedResolution agreed = BreachCaseComputedResolution.agreed(FlagIssueType.QUALITY,
                BigDecimal.ZERO, QualityDisposition.NON_CONFORMING, SignerRole.SELLER, VndAmount.parse("0.00"),
                VndAmount.parse("150000000.00"), SignerRole.BUYER);
        BreachCase mutual = BreachCase.reportedByBuyer(CASE, CONTRACT, MILESTONE,
                BreachReasonCode.QUALITY_BELOW_COMMITTED, List.of(), NOW).endByAgreement(agreed, NOW, "seller");

        RemedyDecision d = calculator.calculateAgreed(mutual, milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, InspectionFeeParty.SELLER,
                NOW);

        assertThat(d.status()).isEqualTo(RemedyStatus.FINALIZED);
        assertThat(d.decisionSource()).isEqualTo(DecisionSource.MUTUAL);
        assertThat(d.reputationEligible()).isTrue();
        assertThat(d.penaltyAmount().wire()).isEqualTo("12000000.00");
        assertThat(d.sellerDepositForfeited().wire()).isEqualTo("12000000.00");
        assertThat(d.inspectionFeeParty()).isEqualTo(InspectionFeeParty.SELLER);
        assertThatThrownBy(() -> calculator.calculate(mutual, milestone("10000"),
                terms("0.08", GoverningLaw.VN_COMMERCIAL_LAW), deposit("22500000.00"), 0, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    private static BreachCase resolvedBySeller() {
        return resolved(computed(FlagIssueType.QUALITY, QualityDisposition.NON_CONFORMING, SignerRole.SELLER, "0.00",
                "150000000.00", SignerRole.BUYER));
    }

    private static BreachCase resolved(BreachCaseComputedResolution computed) {
        return new BreachCase(CASE, CONTRACT, MILESTONE, BreachRequester.BUYER, SignerRole.SELLER,
                BreachReasonCode.QUALITY_BELOW_COMMITTED, BreachSeverity.MINOR, BreachCaseStatus.RESOLVED, List.of(),
                NOW, computed.finalBreachingRole(), DecisionSource.INSPECTOR_L1_5, List.of("r"), NOW, "arbitrator",
                ContestStatus.NOT_APPLICABLE, null, computed, null, List.of(), null, null);
    }

    private static Milestone milestone(String committedKg) {
        return milestone(committedKg, MILESTONE);
    }

    private static Milestone milestone(String committedKg, String milestoneId) {
        LocalDate date = LocalDate.of(2036, 12, 15);
        return Milestone.reconstitute(milestoneId, CONTRACT, 1, new BigDecimal(committedKg), date, 2,
                date.minusDays(10), MilestoneStatus.CONTESTED, FundingStatus.LOCKED, VndAmount.parse("15000.00"),
                VndAmount.parse("150000000.00"), NOW, NOW, 0, null, null, null, null,
                new MilestoneFlag(FlagIssueType.QUALITY, NOW, NOW, ContestedBy.SELLER, CASE));
    }

    /** Price 15,000 over two milestones of 10,000 kg: total 300,000,000; the seller deposit rate is unused here. */
    private static ContractTermsSnapshot terms(String sellerPenaltyRate, GoverningLaw law) {
        ContractTermsInput base = input();
        LegalProfile profile = new LegalProfile(law, "COFFEE_SALE", law.maxContractualPenaltyRate(),
                law == GoverningLaw.VN_COMMERCIAL_LAW ? DamagesPolicy.COMMERCIAL_CUMULATIVE_IF_PROVEN
                        : DamagesPolicy.EXPRESS_PENALTY_ONLY);
        ContractTermsInput negotiated = new ContractTermsInput(VndAmount.parse("15000.00"), base.currency(),
                List.of(new MilestoneTerm(1, new BigDecimal("10000"), LocalDate.of(2036, 12, 15), 2),
                        new MilestoneTerm(2, new BigDecimal("10000"), LocalDate.of(2037, 1, 20), 2)),
                base.qualityTerms(), base.deliveryTerms(), base.inspectionTerms(), base.priceBand(),
                base.priceAdjustmentRule(), base.toleranceRate(), base.shortfallPenaltyThreshold(),
                base.buyerPenaltyRate(), new BigDecimal(sellerPenaltyRate), base.forceMajeureReportWindowDays(),
                base.buyerConfirmWindowDays(), base.buyerDepositRate(), base.disputeFloorReleaseRate(),
                new BigDecimal("0.075"), base.level2InspectorOrg(), profile, base.fundingLeadBusinessDays());
        return new ContractTermsSnapshot(negotiated, template(), goods(), VndAmount.parse("300000000.00"));
    }
}
