package com.agricontract.contract.domain.model.breach;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.consent.SignerRole;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A BreachCase (D80, D81). It starts as an allegation: who reported, who is alleged, why. The allegation has no
 * consequence and the requester is not the breacher: finalBreachingRole is set only by {@link #resolve}, from the
 * deciding request, and null (no fault) is a valid decision. The case is immutable; each transition returns the new
 * state for the caller to persist under the case row lock.
 *
 * @param evidenceFileIds         the evidence files the milestone held when the case was opened
 * @param finalBreachingRole      null until RESOLVED, and null after it for a no-fault decision
 * @param decisionSource          null until RESOLVED
 * @param decisionEvidenceRefs    what the deciding party cited, empty until RESOLVED
 * @param contestStatus           NOT_APPLICABLE until #241 opens the contest window
 * @param computedResolution      D81: set by the confirmed Level 1.5 report (#246), null before and for other cases
 */
public record BreachCase(String breachCaseId, String contractId, String milestoneId, BreachRequester requestedBy,
                         SignerRole allegedBreachingRole, BreachReasonCode breachReasonCode, BreachSeverity severity,
                         BreachCaseStatus status, List<String> evidenceFileIds, Instant reportedAt,
                         SignerRole finalBreachingRole, DecisionSource decisionSource,
                         List<String> decisionEvidenceRefs, Instant resolvedAt, String resolvedByActorId,
                         ContestStatus contestStatus, Instant contestDeadline,
                         BreachCaseComputedResolution computedResolution) {
    public BreachCase {
        Objects.requireNonNull(breachCaseId, "breachCaseId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(breachReasonCode, "breachReasonCode");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reportedAt, "reportedAt");
        Objects.requireNonNull(contestStatus, "contestStatus");
        evidenceFileIds = List.copyOf(evidenceFileIds);
        decisionEvidenceRefs = decisionEvidenceRefs == null ? List.of() : List.copyOf(decisionEvidenceRefs);
        if ((status == BreachCaseStatus.RESOLVED) != (resolvedAt != null && decisionSource != null)) {
            throw new IllegalArgumentException("a case is RESOLVED exactly when it has resolvedAt and decisionSource");
        }
    }

    /** A new REPORTED case opened by the buyer against the seller for a flagged milestone. */
    public static BreachCase reportedByBuyer(String breachCaseId, String contractId, String milestoneId,
                                             BreachReasonCode reason, List<String> evidenceFileIds, Instant now) {
        return new BreachCase(breachCaseId, contractId, milestoneId, BreachRequester.BUYER, SignerRole.SELLER, reason,
                BreachSeverity.MINOR, BreachCaseStatus.REPORTED, evidenceFileIds, now, null, null, List.of(), null,
                null, ContestStatus.NOT_APPLICABLE, null, null);
    }

    /**
     * A new REPORTED case opened by the system against the seller (D77): a mandatory inspection report found the
     * goods PARTIALLY or NON conforming. An allegation only, like any other case.
     */
    public static BreachCase openedBySystem(String breachCaseId, String contractId, String milestoneId,
                                            BreachReasonCode reason, List<String> evidenceFileIds, Instant now) {
        return new BreachCase(breachCaseId, contractId, milestoneId, BreachRequester.SYSTEM, SignerRole.SELLER, reason,
                BreachSeverity.MINOR, BreachCaseStatus.REPORTED, evidenceFileIds, now, null, null, List.of(), null,
                null, ContestStatus.NOT_APPLICABLE, null, null);
    }

    /** An Arbitrator takes the case: REPORTED becomes UNDER_REVIEW. Nothing else changes. */
    public BreachCase review() {
        if (status != BreachCaseStatus.REPORTED) {
            throw notAllowed("A breach case can only be reviewed while REPORTED (" + status + ").");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, BreachCaseStatus.UNDER_REVIEW, evidenceFileIds, reportedAt,
                finalBreachingRole, decisionSource, decisionEvidenceRefs, resolvedAt, resolvedByActorId,
                contestStatus, contestDeadline, computedResolution);
    }

    /**
     * The AttributionDecision (D81): only from UNDER_REVIEW, once. finalRole comes from the deciding request alone
     * (null = no fault) and is never derived from requestedBy or allegedBreachingRole. Guards, in this order:
     * <ul>
     *   <li>a computedResolution is only approved: the role must equal its role and the source must be
     *       INSPECTOR_L1_5, else 409 RESOLUTION_MISMATCH;</li>
     *   <li>no computedResolution on a case whose milestone is still CONTESTED: 409 INSPECTION_REPORT_PENDING;</li>
     *   <li>INSPECTOR_L1_5 without a computedResolution: 409 RESOLUTION_MISMATCH.</li>
     * </ul>
     * The contest window stays NOT_APPLICABLE; opening it is #241. No event: remedy.finalized is #137.
     */
    public BreachCase resolve(SignerRole finalRole, DecisionSource source, List<String> evidenceRefs,
                              boolean milestoneContested, Instant now, String actorId) {
        Objects.requireNonNull(source, "decisionSource");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(actorId, "actorId");
        if (status != BreachCaseStatus.UNDER_REVIEW) {
            throw notAllowed("A breach case can only be resolved while UNDER_REVIEW (" + status + ").");
        }
        if (computedResolution != null) {
            if (finalRole != computedResolution.finalBreachingRole() || source != DecisionSource.INSPECTOR_L1_5) {
                throw new ContractConflictException(ContractConflictException.RESOLUTION_MISMATCH,
                        "The case has a computed resolution from the confirmed Level 1.5 report; the decision must"
                                + " equal it (finalBreachingRole " + computedResolution.finalBreachingRole()
                                + ", decisionSource INSPECTOR_L1_5).");
            }
        } else if (milestoneContested) {
            throw new ContractConflictException(ContractConflictException.INSPECTION_REPORT_PENDING,
                    "The milestone is contested and its Level 1.5 report is not confirmed yet.");
        } else if (source == DecisionSource.INSPECTOR_L1_5) {
            throw new ContractConflictException(ContractConflictException.RESOLUTION_MISMATCH,
                    "INSPECTOR_L1_5 needs a computed resolution; this case has none.");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, BreachCaseStatus.RESOLVED, evidenceFileIds, reportedAt, finalRole, source,
                evidenceRefs == null ? List.of() : evidenceRefs, now, actorId, ContestStatus.NOT_APPLICABLE, null,
                computedResolution);
    }

    /**
     * D81, called by the Level 1.5 report handling (#246): stores the deterministic result the Arbitrator will
     * approve. Only while the case is not RESOLVED, and only once.
     */
    public BreachCase attachComputedResolution(BreachCaseComputedResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        if (status == BreachCaseStatus.RESOLVED) {
            throw notAllowed("A resolved breach case cannot take a computed resolution.");
        }
        if (computedResolution != null) {
            throw notAllowed("This breach case already has a computed resolution.");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, status, evidenceFileIds, reportedAt, finalBreachingRole, decisionSource,
                decisionEvidenceRefs, resolvedAt, resolvedByActorId, contestStatus, contestDeadline, resolution);
    }

    private static ContractConflictException notAllowed(String message) {
        return new ContractConflictException(ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED, message);
    }
}
