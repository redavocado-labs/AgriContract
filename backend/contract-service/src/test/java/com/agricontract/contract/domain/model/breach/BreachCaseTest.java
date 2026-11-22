package com.agricontract.contract.domain.model.breach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.terms.VndAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** #135, #241: review, resolve (D81 guards), the computed resolution and the contest window (GB-50) of a BreachCase. */
class BreachCaseTest {
    private static final Instant AT = Instant.parse("2036-12-10T03:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2036-12-12T03:00:00Z");
    private static final String SELLER = "5a4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a";
    private static final String ARBITRATOR = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";

    @Test
    void review_isOnlyFromReported() {
        BreachCase reviewed = reported().review();

        assertThat(reviewed.status()).isEqualTo(BreachCaseStatus.UNDER_REVIEW);
        assertThat(reviewed.finalBreachingRole()).isNull();
        assertThat(reviewed.allegedBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertCode(reviewed::review, ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
        assertCode(() -> resolved(reviewed).review(), ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
    }

    @Test
    void resolve_isOnlyFromUnderReview_andOnlyOnce() {
        assertCode(() -> reported().resolve(null, DecisionSource.ARBITRATOR, List.of(), false, AT, ARBITRATOR, DEADLINE),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
        BreachCase resolved = resolved(reported().review());

        assertThat(resolved.status()).isEqualTo(BreachCaseStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(AT);
        assertThat(resolved.resolvedByActorId()).isEqualTo(ARBITRATOR);
        assertThat(resolved.decisionSource()).isEqualTo(DecisionSource.ARBITRATOR);
        assertThat(resolved.decisionEvidenceRefs()).containsExactly("ref-1");
        assertThat(resolved.contestStatus()).isEqualTo(ContestStatus.NOT_APPLICABLE);
        assertThat(resolved.contestDeadline()).isNull();
        assertCode(() -> resolved.resolve(null, DecisionSource.ARBITRATOR, List.of(), false, AT, ARBITRATOR, DEADLINE),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
    }

    @Test
    void aNoFaultDecision_isValid_andTheFinalRoleComesOnlyFromTheRequest() {
        BreachCase noFault = resolved(reported().review());
        assertThat(noFault.finalBreachingRole()).isNull();
        assertThat(noFault.allegedBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertThat(noFault.requestedBy()).isEqualTo(BreachRequester.BUYER);

        BreachCase buyerAtFault = reported().review().resolve(SignerRole.BUYER, DecisionSource.ARBITRATOR,
                List.of(), false, AT, ARBITRATOR, DEADLINE);
        assertThat(buyerAtFault.finalBreachingRole()).isEqualTo(SignerRole.BUYER);
        assertThat(buyerAtFault.allegedBreachingRole()).isEqualTo(SignerRole.SELLER);
    }

    @Test
    void aContestedMilestoneWithoutAComputedResolution_isReportPending() {
        BreachCase underReview = reported().review();

        assertCode(() -> underReview.resolve(null, DecisionSource.ARBITRATOR, List.of(), true, AT, ARBITRATOR, DEADLINE),
                ContractConflictException.INSPECTION_REPORT_PENDING);
        assertCode(() -> underReview.resolve(null, DecisionSource.INSPECTOR_L1_5, List.of(), true, AT, ARBITRATOR, DEADLINE),
                ContractConflictException.INSPECTION_REPORT_PENDING);
    }

    @Test
    void inspectorL15WithoutAComputedResolution_isAMismatch() {
        assertCode(() -> reported().review().resolve(null, DecisionSource.INSPECTOR_L1_5, List.of(), false, AT,
                ARBITRATOR, DEADLINE), ContractConflictException.RESOLUTION_MISMATCH);
    }

    @Test
    void aComputedResolution_isOnlyApproved_asItIs() {
        BreachCase withComputed = reported().attachComputedResolution(computed(SignerRole.SELLER)).review();

        assertCode(() -> withComputed.resolve(null, DecisionSource.INSPECTOR_L1_5, List.of(), true, AT, ARBITRATOR, DEADLINE),
                ContractConflictException.RESOLUTION_MISMATCH);
        assertCode(() -> withComputed.resolve(SignerRole.BUYER, DecisionSource.INSPECTOR_L1_5, List.of(), true, AT,
                ARBITRATOR, DEADLINE), ContractConflictException.RESOLUTION_MISMATCH);
        assertCode(() -> withComputed.resolve(SignerRole.SELLER, DecisionSource.ARBITRATOR, List.of(), true, AT,
                ARBITRATOR, DEADLINE), ContractConflictException.RESOLUTION_MISMATCH);

        BreachCase approved = withComputed.resolve(SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5, List.of("r"),
                true, AT, ARBITRATOR, DEADLINE);
        assertThat(approved.status()).isEqualTo(BreachCaseStatus.RESOLVED);
        assertThat(approved.finalBreachingRole()).isEqualTo(SignerRole.SELLER);

        BreachCase noFault = reported().attachComputedResolution(computed(null)).review();
        assertThat(noFault.resolve(null, DecisionSource.INSPECTOR_L1_5, List.of(), true, AT, ARBITRATOR, DEADLINE)
                .finalBreachingRole()).isNull();
        assertCode(() -> noFault.resolve(SignerRole.SELLER, DecisionSource.INSPECTOR_L1_5, List.of(), true, AT,
                ARBITRATOR, DEADLINE), ContractConflictException.RESOLUTION_MISMATCH);
    }

    @Test
    void attach_isOnlyWhileNotResolved_andOnlyOnce() {
        BreachCase attached = reported().attachComputedResolution(computed(null));

        assertThat(attached.computedResolution()).isNotNull();
        assertThat(attached.status()).isEqualTo(BreachCaseStatus.REPORTED);
        assertCode(() -> attached.attachComputedResolution(computed(null)),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
        assertThat(reported().review().attachComputedResolution(computed(null)).status())
                .isEqualTo(BreachCaseStatus.UNDER_REVIEW);
    }

    @Test
    void aResolvedCaseTakesNoComputedResolution() {
        BreachCase resolved = resolved(reported().review());

        assertCode(() -> resolved.attachComputedResolution(computed(null)),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
    }

    @Test
    void aDecisionWithFaultOnAMilestoneCase_opensTheWindow_andAnyOtherDecisionDoesNot() {
        BreachCase open = contestable();
        assertThat(open.contestStatus()).isEqualTo(ContestStatus.OPEN);
        assertThat(open.contestDeadline()).isEqualTo(DEADLINE);

        BreachCase noFault = reported().attachComputedResolution(computed(null)).review().resolve(null,
                DecisionSource.INSPECTOR_L1_5, List.of(), true, AT, ARBITRATOR, DEADLINE);
        assertThat(noFault.contestStatus()).isEqualTo(ContestStatus.NOT_APPLICABLE);
        assertThat(noFault.contestDeadline()).isNull();
        BreachCase noComputed = reported().review().resolve(SignerRole.BUYER, DecisionSource.ARBITRATOR, List.of(),
                false, AT, ARBITRATOR, DEADLINE);
        assertThat(noComputed.contestStatus()).isEqualTo(ContestStatus.NOT_APPLICABLE);
        assertThat(contractLevel().review().resolve(SignerRole.SELLER, DecisionSource.ARBITRATOR, List.of(), false,
                AT, ARBITRATOR, DEADLINE).contestStatus()).isEqualTo(ContestStatus.NOT_APPLICABLE);
    }

    @Test
    void theWindow_isTwoBusinessDaysOfIct_endingWithTheDay_overAWeekendToo() {
        VietnamBusinessDays calendar = new VietnamBusinessDays(Set.of());

        assertThat(calendar.deadline(Instant.parse("2026-11-23T03:00:00Z"), BreachCase.CONTEST_WINDOW_BUSINESS_DAYS))
                .isEqualTo(Instant.parse("2026-11-25T16:59:59.999Z"));
        assertThat(calendar.deadline(Instant.parse("2026-11-27T03:00:00Z"), BreachCase.CONTEST_WINDOW_BUSINESS_DAYS))
                .isEqualTo(Instant.parse("2026-12-01T16:59:59.999Z"));
    }

    @Test
    void contest_isOnceBeforeTheDeadline_byThePartyAtFault() {
        BreachCase open = contestable();

        BreachCase contested = open.contest(SignerRole.SELLER, "wrong", List.of("f1"), DEADLINE.minusSeconds(1),
                SELLER);
        assertThat(contested.contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        assertThat(contested.contestReason()).isEqualTo("wrong");
        assertThat(contested.contestEvidenceFileIds()).containsExactly("f1");
        assertThat(contested.contestedByActorId()).isEqualTo(SELLER);
        assertThat(contested.contestedAt()).isEqualTo(DEADLINE.minusSeconds(1));
        assertThat(contested.status()).isEqualTo(BreachCaseStatus.RESOLVED);
        assertThat(contested.finalBreachingRole()).isEqualTo(SignerRole.SELLER);

        assertThatThrownBy(() -> open.contest(SignerRole.BUYER, "x", List.of(), AT, SELLER))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertThatThrownBy(() -> reported().contest(SignerRole.SELLER, "x", List.of(), AT, SELLER))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertCode(() -> open.contest(SignerRole.SELLER, "x", List.of(), DEADLINE, SELLER),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
        assertCode(() -> contested.contest(SignerRole.SELLER, "x", List.of(), AT, SELLER),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
        BreachCase contractLevel = contractLevel().review().resolve(SignerRole.SELLER, DecisionSource.ARBITRATOR,
                List.of(), false, AT, ARBITRATOR, DEADLINE);
        assertCode(() -> contractLevel.contest(SignerRole.SELLER, "x", List.of(), AT, SELLER),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
    }

    @Test
    void theWindow_expiresOnlyOnceItHasPassed_andNeverAfterAContest() {
        BreachCase open = contestable();

        assertCode(() -> open.expireContestWindow(DEADLINE.minusSeconds(1)),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
        assertThat(open.expireContestWindow(DEADLINE).contestStatus()).isEqualTo(ContestStatus.EXPIRED);
        assertCode(() -> open.contest(SignerRole.SELLER, "x", List.of(), AT, SELLER).expireContestWindow(DEADLINE),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
    }

    @Test
    void contestEvidence_isTheContestersAlone_dedupesAndStopsAtThirtyFiles() {
        BreachCase contested = contestable().contest(SignerRole.SELLER, "x", List.of("f1", "f2"), AT, SELLER);

        BreachCase more = contested.addContestEvidence(SELLER, List.of("f2", "f3"));
        assertThat(more.contestEvidenceFileIds()).containsExactly("f1", "f2", "f3");
        assertThat(more.addContestEvidence(SELLER, List.of("f3", "f1"))).isSameAs(more);
        assertThatThrownBy(() -> contested.addContestEvidence("someone-else", List.of("f9")))
                .isInstanceOf(UnauthorizedContractAccessException.class);
        assertCode(() -> contestable().addContestEvidence(SELLER, List.of("f9")),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);
        List<String> many = java.util.stream.IntStream.range(0, 28).mapToObj(i -> "g" + i).toList();
        assertThat(contested.addContestEvidence(SELLER, many).contestEvidenceFileIds()).hasSize(30);
        assertThatThrownBy(() -> contested.addContestEvidence(SELLER, java.util.stream.IntStream.range(0, 29)
                .mapToObj(i -> "g" + i).toList())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSecondReport_replacesTheComputedResolution_andSendsTheCaseBackToReview() {
        BreachCase contested = contestable().contest(SignerRole.SELLER, "x", List.of(), AT, SELLER);

        BreachCase superseded = contested.supersedeByContestResult(computed(null));
        assertThat(superseded.status()).isEqualTo(BreachCaseStatus.UNDER_REVIEW);
        assertThat(superseded.contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        assertThat(superseded.finalBreachingRole()).isNull();
        assertThat(superseded.decisionSource()).isNull();
        assertThat(superseded.resolvedAt()).isNull();
        assertThat(superseded.computedResolution().finalBreachingRole()).isNull();
        assertCode(() -> contestable().supersedeByContestResult(computed(null)),
                ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED);

        // The decision of the second report is final: no new window, with or without fault.
        BreachCase again = superseded.resolve(null, DecisionSource.INSPECTOR_L1_5, List.of(), false, AT.plusSeconds(1),
                ARBITRATOR, DEADLINE);
        assertThat(again.contestStatus()).isEqualTo(ContestStatus.CONTESTED);
        assertThat(again.contestDeadline()).isEqualTo(DEADLINE);
    }

    @Test
    void theFrozenReasonList_isComplete() {
        assertThat(BreachReasonCode.values()).extracting(Enum::name).containsExactly("NON_DELIVERY", "SHORTFALL",
                "LATE_DELIVERY", "QUALITY_BELOW_COMMITTED", "SIDE_SELLING", "PRODUCTION_SHOCK_NON_FM",
                "FUNDING_FAILURE", "LATE_PAYMENT", "FAILURE_TO_RECEIVE", "WRONGFUL_REJECTION", "FORCE_MAJEURE",
                "MUTUAL_EXIT", "ACTIVATION_FAILURE");
    }

    private static BreachCase resolved(BreachCase underReview) {
        return underReview.resolve(null, DecisionSource.ARBITRATOR, List.of("ref-1"), false, AT, ARBITRATOR, DEADLINE);
    }

    /** A milestone case approved from its computed resolution with the seller at fault: the window is OPEN. */
    private static BreachCase contestable() {
        return reported().attachComputedResolution(computed(SignerRole.SELLER)).review().resolve(SignerRole.SELLER,
                DecisionSource.INSPECTOR_L1_5, List.of(), true, AT, ARBITRATOR, DEADLINE);
    }

    private static BreachCase contractLevel() {
        return new BreachCase("c0000000-0000-4000-8000-000000000001", "c0000000-0000-4000-8000-000000000002", null,
                BreachRequester.BUYER, SignerRole.SELLER, BreachReasonCode.NON_DELIVERY, BreachSeverity.MATERIAL,
                BreachCaseStatus.REPORTED, List.of(), AT, null, null, List.of(), null, null,
                ContestStatus.NOT_APPLICABLE, null, null, null, List.of(), null, null);
    }

    private static BreachCase reported() {
        return BreachCase.reportedByBuyer("c0000000-0000-4000-8000-000000000001",
                "c0000000-0000-4000-8000-000000000002", "c0000000-0000-4000-8000-000000000003",
                BreachReasonCode.SHORTFALL, List.of("f1"), AT);
    }

    private static BreachCaseComputedResolution computed(SignerRole finalRole) {
        return new BreachCaseComputedResolution("c0000000-0000-4000-8000-000000000009", FlagIssueType.QUANTITY,
                new BigDecimal("9000"), null, finalRole, VndAmount.parse("855000000.00"),
                VndAmount.parse("47500000.00"), SignerRole.BUYER);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ContractConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
