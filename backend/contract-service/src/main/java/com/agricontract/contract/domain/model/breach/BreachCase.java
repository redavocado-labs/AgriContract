package com.agricontract.contract.domain.model.breach;

import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.consent.SignerRole;

import java.time.Instant;
import java.util.ArrayList;
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
 * @param contestStatus           NOT_APPLICABLE; OPEN from the resolve of a decision with fault on a milestone case
 *                                until contestDeadline (GB-50); CONTESTED once; EXPIRED when the window passed
 * @param computedResolution      D81: set by the confirmed Level 1.5 report (#246), null before and for other cases;
 *                                replaced by the second report of a contest (D85, D91)
 * @param contestReason           what the contesting party said, null until CONTESTED
 * @param contestEvidenceFileIds  the files named by the contest and its top-ups, empty until CONTESTED
 * @param contestedAt             null until CONTESTED
 * @param contestedByActorId      null until CONTESTED
 */
public record BreachCase(String breachCaseId, String contractId, String milestoneId, BreachRequester requestedBy,
                         SignerRole allegedBreachingRole, BreachReasonCode breachReasonCode, BreachSeverity severity,
                         BreachCaseStatus status, List<String> evidenceFileIds, Instant reportedAt,
                         SignerRole finalBreachingRole, DecisionSource decisionSource,
                         List<String> decisionEvidenceRefs, Instant resolvedAt, String resolvedByActorId,
                         ContestStatus contestStatus, Instant contestDeadline,
                         BreachCaseComputedResolution computedResolution, String contestReason,
                         List<String> contestEvidenceFileIds, Instant contestedAt, String contestedByActorId) {
    /** GB-50: the business days (ICT) a decision with fault waits for a contest. */
    public static final int CONTEST_WINDOW_BUSINESS_DAYS = 2;
    /** D91: the files a contest and its top-ups may name in all. */
    public static final int MAX_CONTEST_EVIDENCE = 30;

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
        contestEvidenceFileIds = contestEvidenceFileIds == null ? List.of() : List.copyOf(contestEvidenceFileIds);
        if ((status == BreachCaseStatus.RESOLVED) != (resolvedAt != null && decisionSource != null)) {
            throw new IllegalArgumentException("a case is RESOLVED exactly when it has resolvedAt and decisionSource");
        }
    }

    /** A new REPORTED case opened by the buyer against the seller for a flagged milestone. */
    public static BreachCase reportedByBuyer(String breachCaseId, String contractId, String milestoneId,
                                             BreachReasonCode reason, List<String> evidenceFileIds, Instant now) {
        return new BreachCase(breachCaseId, contractId, milestoneId, BreachRequester.BUYER, SignerRole.SELLER, reason,
                BreachSeverity.MINOR, BreachCaseStatus.REPORTED, evidenceFileIds, now, null, null, List.of(), null,
                null, ContestStatus.NOT_APPLICABLE, null, null, null, List.of(), null, null);
    }

    /**
     * A new REPORTED case opened by the system against the seller (D77): a mandatory inspection report found the
     * goods PARTIALLY or NON conforming. An allegation only, like any other case.
     */
    public static BreachCase openedBySystem(String breachCaseId, String contractId, String milestoneId,
                                            BreachReasonCode reason, List<String> evidenceFileIds, Instant now) {
        return new BreachCase(breachCaseId, contractId, milestoneId, BreachRequester.SYSTEM, SignerRole.SELLER, reason,
                BreachSeverity.MINOR, BreachCaseStatus.REPORTED, evidenceFileIds, now, null, null, List.of(), null,
                null, ContestStatus.NOT_APPLICABLE, null, null, null, List.of(), null, null);
    }

    /** An Arbitrator takes the case: REPORTED becomes UNDER_REVIEW. Nothing else changes. */
    public BreachCase review() {
        if (status != BreachCaseStatus.REPORTED) {
            throw notAllowed("A breach case can only be reviewed while REPORTED (" + status + ").");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, BreachCaseStatus.UNDER_REVIEW, evidenceFileIds, reportedAt,
                finalBreachingRole, decisionSource, decisionEvidenceRefs, resolvedAt, resolvedByActorId,
                contestStatus, contestDeadline, computedResolution, contestReason, contestEvidenceFileIds, contestedAt,
                contestedByActorId);
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
     * Contest window (GB-50, D85): a decision with fault on a milestone case with a computed resolution (the one that
     * gets a PENDING_CONTEST RemedyDecision) opens it: contestStatus OPEN until {@code contestDeadline}, which the
     * caller computes (resolvedAt + 2 business days). Any other decision stays NOT_APPLICABLE. A case already CONTESTED
     * is deciding the result of its second Level 1.5 report: that decision is final and the window never reopens.
     * No event: remedy.finalized is #137.
     */
    public BreachCase resolve(SignerRole finalRole, DecisionSource source, List<String> evidenceRefs,
                              boolean milestoneContested, Instant now, String actorId, Instant contestDeadline) {
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
        ContestStatus nextContest = contestStatus;
        Instant nextDeadline = this.contestDeadline;
        if (contestStatus != ContestStatus.CONTESTED) {
            boolean opens = finalRole != null && milestoneId != null && computedResolution != null;
            nextContest = opens ? ContestStatus.OPEN : ContestStatus.NOT_APPLICABLE;
            nextDeadline = opens ? Objects.requireNonNull(contestDeadline, "contestDeadline") : null;
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, BreachCaseStatus.RESOLVED, evidenceFileIds, reportedAt, finalRole, source,
                evidenceRefs == null ? List.of() : evidenceRefs, now, actorId, nextContest, nextDeadline,
                computedResolution, contestReason, contestEvidenceFileIds, contestedAt, contestedByActorId);
    }

    /**
     * D81, called by the Level 1.5 report handling (#246): stores the deterministic result the Arbitrator will
     * approve. Only while the case is not RESOLVED, and only once.
     */
    public BreachCase attachComputedResolution(BreachCaseComputedResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        if (resolution.isAgreed()) {
            throw new IllegalArgumentException("an agreed position is attached by endByAgreement only");
        }
        if (status == BreachCaseStatus.RESOLVED) {
            throw notAllowed("A resolved breach case cannot take a computed resolution.");
        }
        if (computedResolution != null) {
            throw notAllowed("This breach case already has a computed resolution.");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, status, evidenceFileIds, reportedAt, finalBreachingRole, decisionSource,
                decisionEvidenceRefs, resolvedAt, resolvedByActorId, contestStatus, contestDeadline, resolution,
                contestReason, contestEvidenceFileIds, contestedAt, contestedByActorId);
    }

    /**
     * D85, D91: the second Level 1.5 report of a contest. Only a RESOLVED case that is CONTESTED: the new computed
     * resolution replaces the old one, the case goes back to UNDER_REVIEW with its decision cleared (the Arbitrator
     * approves the new result as D81) and contestStatus stays CONTESTED.
     */
    public BreachCase supersedeByContestResult(BreachCaseComputedResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        if (status != BreachCaseStatus.RESOLVED || contestStatus != ContestStatus.CONTESTED) {
            throw notAllowed("Only a contested, resolved breach case takes the result of its second inspection.");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, BreachCaseStatus.UNDER_REVIEW, evidenceFileIds, reportedAt, null, null,
                List.of(), null, null, contestStatus, contestDeadline, resolution, contestReason,
                contestEvidenceFileIds, contestedAt, contestedByActorId);
    }

    /**
     * D57, D92: the parties ended the dispute themselves, by a concession or an agreed settlement. The case is RESOLVED
     * at once with decisionSource MUTUAL and the agreed position as its computed resolution (finalBreachingRole taken
     * from it, null for no fault). Allowed while nothing decides the case yet: REPORTED or UNDER_REVIEW without a
     * computed resolution, or a decision under contest (RESOLVED and CONTESTED, before the contest's report replaced
     * it). Never for a case the system opened (mandatory inspection). No contest window: contestStatus stays CONTESTED
     * for a contested decision and is NOT_APPLICABLE otherwise.
     */
    public BreachCase endByAgreement(BreachCaseComputedResolution agreed, Instant now, String actorId) {
        Objects.requireNonNull(agreed, "agreed");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(actorId, "actorId");
        if (!agreed.isAgreed()) {
            throw new IllegalArgumentException("an agreed exit takes an agreed resolution, not a report's");
        }
        if (requestedBy == BreachRequester.SYSTEM || milestoneId == null) {
            throw exitNotAllowed("A case opened by the system, or one without a milestone, cannot end by agreement.");
        }
        boolean contestedDecision = status == BreachCaseStatus.RESOLVED && contestStatus == ContestStatus.CONTESTED;
        if (!contestedDecision && (status == BreachCaseStatus.RESOLVED || computedResolution != null)) {
            throw exitNotAllowed("The dispute is already decided or waits for the Arbitrator (" + status + ").");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, BreachCaseStatus.RESOLVED, evidenceFileIds, reportedAt,
                agreed.finalBreachingRole(), DecisionSource.MUTUAL, List.of(), now, actorId,
                contestedDecision ? ContestStatus.CONTESTED : ContestStatus.NOT_APPLICABLE,
                contestedDecision ? contestDeadline : null, agreed, contestReason, contestEvidenceFileIds,
                contestedAt, contestedByActorId);
    }

    /** True when the case was opened by the system (a mandatory inspection, D77), not by a party's flag. */
    public boolean openedBySystem() {
        return requestedBy == BreachRequester.SYSTEM;
    }

    /**
     * D91: whether this party may upload contest evidence for the case's milestone now. The blamed party while the
     * window is OPEN (so files are ready for the contest); the contester while CONTESTED until the contest's Level 1.5
     * report is stored. Never for a contract-level case.
     */
    public boolean allowsContestEvidenceUpload(SignerRole role, String actorId, Instant now,
                                               boolean contestReportStored) {
        if (status != BreachCaseStatus.RESOLVED || milestoneId == null) {
            return false;
        }
        if (contestStatus == ContestStatus.OPEN) {
            return role == finalBreachingRole && contestDeadline != null && now.isBefore(contestDeadline);
        }
        return contestStatus == ContestStatus.CONTESTED && actorId.equals(contestedByActorId) && !contestReportStored;
    }

    /** 403 unless the caller's role is the finalBreachingRole: only the party the decision blames can contest it. */
    public void requireContester(SignerRole callerRole) {
        if (finalBreachingRole == null || callerRole != finalBreachingRole) {
            throw new UnauthorizedContractAccessException("Only the party the decision holds at fault can contest it.");
        }
    }

    /**
     * The one-time contest of the decision (GB-50, D91), by the party whose role is the finalBreachingRole (403
     * otherwise). 409 for a contract-level case (no milestone), a window that is not OPEN, or a deadline that has
     * passed. Nothing else changes: the decision stays until the second Level 1.5 report.
     */
    public BreachCase contest(SignerRole callerRole, String reason, List<String> evidenceIds, Instant now,
                              String actorId) {
        requireContester(callerRole);
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(actorId, "actorId");
        if (milestoneId == null) {
            throw notAllowed("A contract-level breach case cannot be contested on the platform.");
        }
        if (contestStatus != ContestStatus.OPEN || contestDeadline == null) {
            throw notAllowed("The contest window is not open (" + contestStatus + ").");
        }
        if (!now.isBefore(contestDeadline)) {
            throw notAllowed("The contest window closed at " + contestDeadline + ".");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, status, evidenceFileIds, reportedAt, finalBreachingRole, decisionSource,
                decisionEvidenceRefs, resolvedAt, resolvedByActorId, ContestStatus.CONTESTED, contestDeadline,
                computedResolution, reason, evidenceIds, now, actorId);
    }

    /**
     * D91: the contester adds files to the contest. 403 for anyone else, 409 unless CONTESTED, and 400
     * (IllegalArgumentException) beyond {@link #MAX_CONTEST_EVIDENCE} files in all. Ids already attached are ignored;
     * when nothing is new this case is returned unchanged.
     */
    public BreachCase addContestEvidence(String actorId, List<String> fileIds) {
        requireContestOwner(actorId);
        if (contestStatus != ContestStatus.CONTESTED) {
            throw notAllowed("Evidence can only be added to a contested decision (" + contestStatus + ").");
        }
        List<String> merged = new ArrayList<>(contestEvidenceFileIds);
        for (String id : fileIds) {
            if (!merged.contains(id)) {
                merged.add(id);
            }
        }
        if (merged.size() == contestEvidenceFileIds.size()) {
            return this;
        }
        if (merged.size() > MAX_CONTEST_EVIDENCE) {
            throw new IllegalArgumentException("A contest can name at most " + MAX_CONTEST_EVIDENCE + " files in all");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, status, evidenceFileIds, reportedAt, finalBreachingRole, decisionSource,
                decisionEvidenceRefs, resolvedAt, resolvedByActorId, contestStatus, contestDeadline,
                computedResolution, contestReason, merged, contestedAt, contestedByActorId);
    }

    /** 403 unless the caller is the party that contested (a case nobody contested has no owner yet: the 409 follows). */
    public void requireContestOwner(String actorId) {
        Objects.requireNonNull(actorId, "actorId");
        if (contestedByActorId != null && !contestedByActorId.equals(actorId)) {
            throw new UnauthorizedContractAccessException("Only the party that contested can add contest evidence.");
        }
    }

    /** The window passed with no contest: OPEN becomes EXPIRED. 409 when it is not OPEN or has not passed yet. */
    public BreachCase expireContestWindow(Instant now) {
        Objects.requireNonNull(now, "now");
        if (contestStatus != ContestStatus.OPEN || contestDeadline == null || now.isBefore(contestDeadline)) {
            throw notAllowed("The contest window is not over (" + contestStatus + ").");
        }
        return new BreachCase(breachCaseId, contractId, milestoneId, requestedBy, allegedBreachingRole,
                breachReasonCode, severity, status, evidenceFileIds, reportedAt, finalBreachingRole, decisionSource,
                decisionEvidenceRefs, resolvedAt, resolvedByActorId, ContestStatus.EXPIRED, contestDeadline,
                computedResolution, contestReason, contestEvidenceFileIds, contestedAt, contestedByActorId);
    }

    private static ContractConflictException exitNotAllowed(String message) {
        return new ContractConflictException(ContractConflictException.DISPUTE_EXIT_NOT_ALLOWED, message);
    }

    private static ContractConflictException notAllowed(String message) {
        return new ContractConflictException(ContractConflictException.BREACH_CASE_ACTION_NOT_ALLOWED, message);
    }
}
