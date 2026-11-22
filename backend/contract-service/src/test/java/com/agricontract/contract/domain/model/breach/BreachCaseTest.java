package com.agricontract.contract.domain.model.breach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.terms.VndAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #135: review, resolve (D81 guards) and the computed resolution of a BreachCase. */
class BreachCaseTest {
    private static final Instant AT = Instant.parse("2036-12-10T03:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2036-12-12T03:00:00Z");
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
    void theFrozenReasonList_isComplete() {
        assertThat(BreachReasonCode.values()).extracting(Enum::name).containsExactly("NON_DELIVERY", "SHORTFALL",
                "LATE_DELIVERY", "QUALITY_BELOW_COMMITTED", "SIDE_SELLING", "PRODUCTION_SHOCK_NON_FM",
                "FUNDING_FAILURE", "LATE_PAYMENT", "FAILURE_TO_RECEIVE", "WRONGFUL_REJECTION", "FORCE_MAJEURE",
                "MUTUAL_EXIT", "ACTIVATION_FAILURE");
    }

    private static BreachCase resolved(BreachCase underReview) {
        return underReview.resolve(null, DecisionSource.ARBITRATOR, List.of("ref-1"), false, AT, ARBITRATOR, DEADLINE);
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
