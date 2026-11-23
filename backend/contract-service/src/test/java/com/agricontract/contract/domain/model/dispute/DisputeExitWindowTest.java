package com.agricontract.contract.domain.model.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.exception.ContractConflictException;
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
import com.agricontract.contract.domain.model.inspection.InspectionReportPurpose;
import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.inspection.Level15Assignment;
import com.agricontract.contract.domain.model.inspection.Level15Request;
import com.agricontract.contract.domain.model.milestone.BuyerReceipt;
import com.agricontract.contract.domain.model.milestone.ContestedBy;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.FundingStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneFlag;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.milestone.SellerWeighing;
import com.agricontract.contract.domain.model.quality.MeasurementStatus;
import com.agricontract.contract.domain.model.remedy.InspectionFeeParty;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyFundType;
import com.agricontract.contract.domain.model.remedy.RemedyLeg;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.remedy.RemedyType;
import com.agricontract.contract.domain.model.terms.VndAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** D57, D92: the exit window, the positions and the agreed resolutions (P 15,000, locked 150,000,000). */
class DisputeExitWindowTest {
    private static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");
    private static final String CASE = "case-1";

    @Test
    void awaitingSellerResponse_allowsConcessionOnly_withBothPositions() {
        DisputeExitWindow w = window(milestone(MilestoneStatus.AWAITING_SELLER_RESPONSE, FlagIssueType.QUANTITY,
                "10000", "9800", null), null, null, Map.of()).orElseThrow();

        assertThat(w.stage()).isEqualTo(DisputeExitStage.AWAITING_SELLER_RESPONSE);
        assertThat(w.concedeAllowed()).isTrue();
        assertThat(w.settlementAllowed()).isFalse();
        assertThat(w.buyerPosition().wire()).isEqualTo("147000000.00");
        assertThat(w.sellerPosition().wire()).isEqualTo("150000000.00");
        assertThatThrownBy(w::requireSettlementAllowed).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(ContractConflictException.DISPUTE_EXIT_NOT_ALLOWED));
    }

    @Test
    void positions_areCappedAtTheLockedBatch_andAQualityFlagPutsTheBuyerAtZero() {
        DisputeExitWindow w = window(milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUALITY, "10500.4", "9800",
                CASE), openCase(), null, Map.of()).orElseThrow();

        assertThat(w.stage()).isEqualTo(DisputeExitStage.CONTESTED_FLAG);
        assertThat(w.settlementAllowed()).isTrue();
        assertThat(w.buyerPosition().wire()).isEqualTo("0.00");
        assertThat(w.sellerPosition().wire()).isEqualTo("150000000.00");
    }

    @Test
    void sellerPosition_roundsHalfUpToTheDong() {
        DisputeExitWindow w = window(milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUANTITY, "9001", "8000.001",
                CASE, "15000.50"), openCase(), null, Map.of()).orElseThrow();

        assertThat(w.sellerPosition().wire()).isEqualTo("135019501.00");
        assertThat(w.buyerPosition().wire()).isEqualTo("120004015.00");
    }

    @Test
    void noWindow_onceAReportDecided_forASystemCase_orAfterTheDecision() {
        Milestone contested = milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUALITY, "10000", "9800", CASE);
        assertThat(window(contested, openCase(), null,
                Map.of(InspectionReportPurpose.INITIAL, MeasurementStatus.COMPLETE))).isEmpty();
        assertThat(window(contested, openCase(), null,
                Map.of(InspectionReportPurpose.INITIAL, MeasurementStatus.INCONCLUSIVE,
                        InspectionReportPurpose.RERUN, MeasurementStatus.COMPLETE))).isEmpty();
        BreachCase system = BreachCase.openedBySystem(CASE, "contract", "m", BreachReasonCode.QUALITY_BELOW_COMMITTED,
                List.of(), NOW);
        assertThat(window(contested, system, null, Map.of())).isEmpty();
        assertThat(window(contested, openCase().review().attachComputedResolution(reportResolution()), null,
                Map.of())).isEmpty();
        assertThat(window(milestone(MilestoneStatus.SETTLED, FlagIssueType.QUALITY, "10000", "9800", CASE),
                openCase(), null, Map.of())).isEmpty();
    }

    @Test
    void aContractWithMandatoryInspection_hasNoWindow_awaitingOrContested() {
        assertThat(DisputeExitWindow.assess(milestone(MilestoneStatus.AWAITING_SELLER_RESPONSE,
                FlagIssueType.QUANTITY, "10000", "9800", null), null, null, Map.of(), true)).isEmpty();
        assertThat(DisputeExitWindow.assess(milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUALITY, "10000",
                "9800", CASE), openCase(), null, Map.of(), true)).isEmpty();
    }

    @Test
    void anInconclusiveFirstReport_keepsTheWindowOpen_andIsRemembered() {
        DisputeExitWindow w = window(milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUALITY, "10000", "9800",
                CASE), openCase(), null, Map.of(InspectionReportPurpose.INITIAL, MeasurementStatus.INCONCLUSIVE))
                .orElseThrow();

        assertThat(w.pendingRoundInconclusive()).isTrue();
    }

    @Test
    void aContestedDecision_isAWindow_untilTheContestReportIsComplete_buyerPositionIsTheDecision() {
        Milestone contested = milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUALITY, "10000", "9800", CASE);
        BreachCase underContest = contestedDecision();
        RemedyDecision pending = pendingDecision();

        DisputeExitWindow w = window(contested, underContest, pending, Map.of(InspectionReportPurpose.INITIAL,
                MeasurementStatus.COMPLETE)).orElseThrow();
        assertThat(w.stage()).isEqualTo(DisputeExitStage.CONTESTED_DECISION);
        assertThat(w.buyerPosition().wire()).isEqualTo("0.00");
        assertThat(window(contested, underContest, pending, Map.of(InspectionReportPurpose.CONTEST,
                MeasurementStatus.COMPLETE))).isEmpty();
    }

    @Test
    void sellerConcession_onQuantity_isTheBuyersFigureWithNoFault_onQuality_isNonConformingWithFault() {
        DisputeExitWindow quantity = window(milestone(MilestoneStatus.AWAITING_SELLER_RESPONSE,
                FlagIssueType.QUANTITY, "10000", "9800", null), null, null, Map.of()).orElseThrow();
        BreachCaseComputedResolution q = quantity.concession(SignerRole.SELLER);
        assertThat(q.isAgreed()).isTrue();
        assertThat(q.finalBreachingRole()).isNull();
        assertThat(q.sellerEntitlementAmount().wire()).isEqualTo("147000000.00");
        assertThat(q.buyerRefundAmount().wire()).isEqualTo("3000000.00");
        assertThat(q.prevailingRole()).isEqualTo(SignerRole.BUYER);

        DisputeExitWindow quality = window(milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUALITY, "10000",
                "9800", CASE), openCase(), null, Map.of()).orElseThrow();
        BreachCaseComputedResolution nc = quality.concession(SignerRole.SELLER);
        assertThat(nc.qualityDisposition()).isEqualTo(QualityDisposition.NON_CONFORMING);
        assertThat(nc.finalBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertThat(nc.buyerRefundAmount().wire()).isEqualTo("150000000.00");

        BreachCaseComputedResolution buyer = quality.concession(SignerRole.BUYER);
        assertThat(buyer.finalBreachingRole()).isNull();
        assertThat(buyer.sellerEntitlementAmount().wire()).isEqualTo("150000000.00");
        assertThat(buyer.prevailingRole()).isEqualTo(SignerRole.SELLER);
    }

    @Test
    void settlement_bothEndsIncluded_outsideIs400() {
        DisputeExitWindow w = window(milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUANTITY, "9000", "8000",
                CASE), openCase(), null, Map.of()).orElseThrow();

        assertThat(w.settlement(VndAmount.parse("120000000.00")).sellerEntitlementAmount().wire())
                .isEqualTo("120000000.00");
        BreachCaseComputedResolution top = w.settlement(VndAmount.parse("135000000.00"));
        assertThat(top.buyerRefundAmount().wire()).isEqualTo("15000000.00");
        assertThat(top.acceptedQuantityKg()).isEqualByComparingTo("9000");
        assertThat(top.prevailingRole()).isNull();
        assertThatThrownBy(() -> w.settlement(VndAmount.parse("119999999.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.settlement(VndAmount.parse("135000001.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endByAgreement_resolvesMutual_withNoWindow_andRefusesADecidedOrSystemCase() {
        BreachCaseComputedResolution agreed = BreachCaseComputedResolution.agreed(FlagIssueType.QUALITY,
                BigDecimal.ZERO, QualityDisposition.NON_CONFORMING, SignerRole.SELLER, VndAmount.parse("0.00"),
                VndAmount.parse("150000000.00"), SignerRole.BUYER);

        BreachCase resolved = openCase().endByAgreement(agreed, NOW, "seller");
        assertThat(resolved.status()).isEqualTo(BreachCaseStatus.RESOLVED);
        assertThat(resolved.decisionSource()).isEqualTo(DecisionSource.MUTUAL);
        assertThat(resolved.finalBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertThat(resolved.contestStatus()).isEqualTo(ContestStatus.NOT_APPLICABLE);

        BreachCase contested = contestedDecision().endByAgreement(agreed, NOW, "buyer");
        assertThat(contested.contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        assertThat(contested.computedResolution()).isSameAs(agreed);

        assertThatThrownBy(() -> resolved.endByAgreement(agreed, NOW, "x"))
                .isInstanceOfSatisfying(ContractConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ContractConflictException.DISPUTE_EXIT_NOT_ALLOWED));
        assertThatThrownBy(() -> BreachCase.openedBySystem(CASE, "c", "m", BreachReasonCode.QUALITY_BELOW_COMMITTED,
                List.of(), NOW).endByAgreement(agreed, NOW, "x")).isInstanceOf(ContractConflictException.class);
        assertThatThrownBy(() -> openCase().attachComputedResolution(agreed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void feeRule_inconclusiveFirst_thenAssignmentPastTheFreeWindow_elseNone() {
        DisputeExitWindow inconclusive = window(milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUALITY, "10000",
                "9800", CASE), openCase(), null, Map.of(InspectionReportPurpose.INITIAL,
                MeasurementStatus.INCONCLUSIVE)).orElseThrow();
        assertThat(InspectionFeeRule.forExit(DisputeExitType.SETTLE, null, inconclusive, null, null, null, NOW))
                .isEqualTo(InspectionFeeParty.SPLIT);
        assertThat(InspectionFeeRule.forExit(DisputeExitType.CONCEDE, SignerRole.BUYER, inconclusive, null, null,
                null, NOW)).isEqualTo(InspectionFeeParty.BUYER);

        DisputeExitWindow w = window(milestone(MilestoneStatus.CONTESTED, FlagIssueType.QUALITY, "10000", "9800",
                CASE), openCase(), null, Map.of()).orElseThrow();
        Level15Request request = new Level15Request("r", "m", InspectionRequestReason.DISPUTE_ROUTED, "seller",
                NOW.minusSeconds(7200), null, null);
        Level15Assignment inWindow = new Level15Assignment("c", "m", NOW.minusSeconds(3600), NOW.plusSeconds(60),
                NOW.plusSeconds(86400));
        Level15Assignment passed = new Level15Assignment("c", "m", NOW.minusSeconds(3600), NOW.minusSeconds(1),
                NOW.plusSeconds(86400));
        Level15Assignment ofAnEarlierRound = new Level15Assignment("c", "m", NOW.minusSeconds(9000),
                NOW.minusSeconds(8000), NOW.plusSeconds(86400));

        assertThat(InspectionFeeRule.forExit(DisputeExitType.CONCEDE, SignerRole.BUYER, w, request, null,
                SignerRole.SELLER, NOW)).isNull();
        assertThat(InspectionFeeRule.forExit(DisputeExitType.CONCEDE, SignerRole.BUYER, w, request, inWindow,
                SignerRole.SELLER, NOW)).isNull();
        assertThat(InspectionFeeRule.forExit(DisputeExitType.CONCEDE, SignerRole.BUYER, w, request,
                ofAnEarlierRound, SignerRole.SELLER, NOW)).isNull();
        assertThat(InspectionFeeRule.forExit(DisputeExitType.CONCEDE, SignerRole.BUYER, w, request, passed,
                SignerRole.SELLER, NOW)).isEqualTo(InspectionFeeParty.BUYER);
        assertThat(InspectionFeeRule.forExit(DisputeExitType.SETTLE, null, w, request, passed, SignerRole.SELLER,
                NOW)).isEqualTo(InspectionFeeParty.SELLER);
    }

    // ---- fixtures ----

    private static Optional<DisputeExitWindow> window(Milestone m, BreachCase c, RemedyDecision latest,
                                                      Map<InspectionReportPurpose, MeasurementStatus> reports) {
        return DisputeExitWindow.assess(m, c, latest, reports, false);
    }

    private static Milestone milestone(MilestoneStatus status, FlagIssueType issue, String sellerKg, String buyerKg,
                                       String caseId) {
        return milestone(status, issue, sellerKg, buyerKg, caseId, "15000.00");
    }

    private static Milestone milestone(MilestoneStatus status, FlagIssueType issue, String sellerKg, String buyerKg,
                                       String caseId, String price) {
        LocalDate date = LocalDate.of(2036, 12, 15);
        MilestoneFlag flag = caseId == null ? new MilestoneFlag(issue, NOW, null, null, null)
                : new MilestoneFlag(issue, NOW, NOW, ContestedBy.SELLER, caseId);
        return Milestone.reconstitute("m", "contract", 1, new BigDecimal("10000"), date, 2, date.minusDays(10),
                status, FundingStatus.LOCKED, VndAmount.parse(price), VndAmount.parse("150000000.00"), NOW, NOW,
                0, null, new SellerWeighing(new BigDecimal(sellerKg), "f1", null, NOW, 1),
                new BuyerReceipt(new BigDecimal(buyerKg), "f2", null, NOW), null, flag);
    }

    private static BreachCase openCase() {
        return BreachCase.reportedByBuyer(CASE, "contract", "m", BreachReasonCode.QUALITY_BELOW_COMMITTED, List.of(),
                NOW);
    }

    private static BreachCaseComputedResolution reportResolution() {
        return new BreachCaseComputedResolution("report-1", FlagIssueType.QUALITY, new BigDecimal("9800"),
                QualityDisposition.NON_CONFORMING, SignerRole.SELLER, VndAmount.parse("0.00"),
                VndAmount.parse("150000000.00"), SignerRole.BUYER);
    }

    private static BreachCase contestedDecision() {
        return new BreachCase(CASE, "contract", "m", BreachRequester.BUYER, SignerRole.SELLER,
                BreachReasonCode.QUALITY_BELOW_COMMITTED, BreachSeverity.MINOR, BreachCaseStatus.RESOLVED, List.of(),
                NOW, SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5, List.of(), NOW, "arb", ContestStatus.CONTESTED,
                NOW.plusSeconds(86400), reportResolution(), "wrong", List.of(), NOW, "seller");
    }

    private static RemedyDecision pendingDecision() {
        return new RemedyDecision("d1", "contract", CASE, "m", RemedyStatus.PENDING_CONTEST, SignerRole.SELLER,
                BreachReasonCode.QUALITY_BELOW_COMMITTED, QualityDisposition.NON_CONFORMING,
                DecisionSource.INSPECTOR_L1_5, VndAmount.parse("12000000.00"), VndAmount.parse("0.00"), 0,
                List.of(new RemedyLeg("l1", RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT,
                        SignerRole.BUYER, VndAmount.parse("150000000.00"))), NOW, null);
    }
}
