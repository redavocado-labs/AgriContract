package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.InspectionReportCommands;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.ContractCompletion;
import com.agricontract.contract.application.service.MilestoneCleanSettlement;
import com.agricontract.contract.application.service.MilestoneContestStep;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseComputedResolution;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.breach.ContestStatus;
import com.agricontract.contract.domain.model.inspection.InspectionReport;
import com.agricontract.contract.domain.model.inspection.InspectionReportPurpose;
import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneSettlement;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.quality.MeasurementStatus;
import com.agricontract.contract.domain.model.quality.PrevailingRoleRule;
import com.agricontract.contract.domain.model.quality.QualityAssessment;
import com.agricontract.contract.domain.model.quality.QualityDisposition;
import com.agricontract.contract.domain.model.quality.QualityDispositionCalculator;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.InspectionReportRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import com.agricontract.contract.domain.repository.SettlementProposalRepository;
import com.agricontract.contract.application.service.Level15Inspections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * inspection.report_confirmed, Level 1.5 (D81, D83, D85, D89, D90). Runs in the consumer's transaction, under the
 * contract and milestone row locks (contract first, as every contract-service write does). The hashes were verified by
 * the consumer. A report that does not fit is acknowledged and ignored with a WARN; nothing is written for it.
 *
 * <p>Every COMPLETE report is computed in full (accepted quantity and quality). Then:
 * <ul>
 *   <li>milestone CONTESTED with an open BreachCase: the computed resolution is attached to the case; the Arbitrator
 *       approves it later (#135). No money moves here;</li>
 *   <li>milestone BUYER_RECEIVED of a MANDATORY_BEFORE_SETTLEMENT contract, nobody flagged: CONFORMING settles the
 *       milestone at once (INSPECTED_CONFORMING, milestone.settled); PARTIALLY or NON conforming makes the system open a
 *       BreachCase (milestone CONTESTED, breach.reported) with the computed resolution attached;</li>
 *   <li>INCONCLUSIVE: the report is stored; a first one asks inspection-service again with the reason of the
 *       original request (a second INCONCLUSIVE is final: the milestone and its money stay locked, WARN
 *       inspection_inconclusive_final, an operator handles it).</li>
 * </ul>
 * Rounds (D85, D91, V24, V25): each report has a purpose, at most one of each per milestone: INITIAL, RERUN (only after
 * an INCONCLUSIVE first report) and CONTEST (only while the case is CONTESTED). The re-run never uses up the contest.
 * The contest report replaces the case's computed resolution, the case goes back to UNDER_REVIEW and the pending
 * RemedyDecision is SUPERSEDED. Any other report is ignored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplyInspectionReportUseCase {
    private final ContractRepository contracts;
    private final MilestoneRepository milestones;
    private final BreachCaseRepository breachCases;
    private final InspectionReportRepository reports;
    private final RemedyDecisionRepository remedyDecisions;
    private final MilestoneContestStep contestStep;
    private final ContractEventPublisher events;
    private final Level15Inspections level15;
    private final ContractCompletion completion;
    private final SettlementProposalRepository proposals;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void execute(InspectionReportCommands.ApplyConfirmed command) {
        transactions.requireTransaction();
        InspectionReport report = command.report();
        Contract contract = contracts.findByIdForUpdate(new ContractId(report.contractId())).orElse(null);
        if (contract == null) {
            ignore(report, "unknown_contract");
            return;
        }
        Milestone milestone = milestones.findByIdForUpdate(report.milestoneId()).orElse(null);
        if (milestone == null) {
            ignore(report, "unknown_milestone");
            return;
        }
        if (!milestone.contractId().equals(report.contractId())) {
            ignore(report, "contract_mismatch");
            return;
        }
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            ignore(report, "contract_not_active");
            return;
        }
        boolean contested = milestone.status() == MilestoneStatus.CONTESTED;
        if (!contested && milestone.status() != MilestoneStatus.BUYER_RECEIVED) {
            ignore(report, "milestone_" + milestone.status());
            return;
        }
        if (!contested && !MilestoneCleanSettlement.requiresInspection(contract)) {
            ignore(report, "inspection_not_requested");
            return;
        }
        boolean complete = report.measurementStatus() == MeasurementStatus.COMPLETE;
        if (complete && (milestone.batchAmount() == null || milestone.effectiveUnitPrice() == null
                || milestone.buyerReceipt() == null || milestone.sellerWeighing() == null)) {
            ignore(report, "no_locked_batch");
            return;
        }
        BreachCase breachCase = null;
        if (contested) {
            breachCase = milestone.flag() == null || milestone.flag().breachCaseId() == null ? null
                    : breachCases.findByIdForUpdate(milestone.flag().breachCaseId()).orElse(null);
            if (breachCase == null) {
                ignore(report, "breach_case_missing");
                return;
            }
        }

        InspectionReportPurpose purpose = purposeOf(breachCase, milestone);
        if (purpose == null) {
            ignore(report, "report_already_stored");
            return;
        }
        boolean contestRound = purpose == InspectionReportPurpose.CONTEST;
        RemedyDecision pending = null;
        if (contestRound) {
            pending = remedyDecisions.findByBreachCaseId(breachCase.breachCaseId())
                    .flatMap(d -> remedyDecisions.findByIdForUpdate(d.remedyDecisionId())).orElse(null);
            if (pending == null || pending.status() != RemedyStatus.PENDING_CONTEST) {
                ignore(report, "decision_not_pending");
                return;
            }
        } else if (breachCase != null
                && (breachCase.status() == BreachCaseStatus.RESOLVED || breachCase.computedResolution() != null)) {
            ignore(report, "breach_case_closed");
            return;
        }

        QualityAssessment assessment = QualityDispositionCalculator.assess(
                contract.getCoffeeTerms().snapshot().negotiated().qualityTerms(), report.measurementStatus(),
                report.actualMetrics(), report.acceptedQuantityKg(), milestone.effectiveUnitPrice(),
                milestone.batchAmount());
        reports.insert(report, assessment, purpose);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        // D92: the request this report answers ends; a COMPLETE report decides the round, so an open settlement
        // proposal lapses (it never stopped the inspection).
        level15.reported(milestone.milestoneId(), now);
        if (complete && proposals.lapseOpen(milestone.milestoneId(), now)) {
            log.info("event=settlement_proposal_lapsed reason=report_complete milestoneId={} reportId={}",
                    milestone.milestoneId(), report.reportId());
        }
        if (!complete) {
            inconclusive(contract, milestone, report, contested, purpose, now);
            return;
        }

        if (contestRound) {
            supersede(breachCase, pending, milestone, report, assessment, now);
        } else if (contested) {
            attach(breachCase, milestone.flag().issueType(), milestone, report, assessment);
        } else if (assessment.disposition() == QualityDisposition.CONFORMING) {
            settle(contract, milestone, report, assessment, now);
        } else {
            BreachCase opened = contestStep.openByInspection(contract, milestone, now);
            attach(opened, FlagIssueType.QUALITY, milestone, report, assessment);
        }
    }

    /**
     * INCONCLUSIVE: nothing is decided. The first one asks again with the reason of the original request (a dispute
     * for a contested milestone, a mandatory inspection otherwise); the second is final.
     */
    private void inconclusive(Contract contract, Milestone milestone, InspectionReport report, boolean contested,
                              InspectionReportPurpose purpose, Instant now) {
        if (purpose != InspectionReportPurpose.INITIAL) {
            log.warn("event=inspection_inconclusive_final reportId={} contractId={} milestoneId={} eventId={}",
                    report.reportId(), report.contractId(), report.milestoneId(), report.receivedEventId());
            return;
        }
        log.warn("event=inspection_report_inconclusive reportId={} contractId={} milestoneId={} eventId={}",
                report.reportId(), report.contractId(), report.milestoneId(), report.receivedEventId());
        level15.request(contract, milestone, contested
                ? InspectionRequestReason.DISPUTE_ROUTED : InspectionRequestReason.MANDATORY_INSPECTION, null, now);
    }

    /** D91: the second report of a contest replaces the computed resolution and supersedes the pending decision. */
    private void supersede(BreachCase breachCase, RemedyDecision pending, Milestone milestone, InspectionReport report,
                           QualityAssessment assessment, Instant now) {
        BreachCaseComputedResolution resolution = resolutionOf(milestone.flag().issueType(), milestone, report,
                assessment);
        breachCases.update(breachCase.supersedeByContestResult(resolution));
        remedyDecisions.markSuperseded(pending.remedyDecisionId(), now);
        log.info("event=contest_result_attached breachCaseId={} reportId={} supersededRemedyDecisionId={}"
                        + " disposition={} finalBreachingRole={}", breachCase.breachCaseId(), report.reportId(),
                pending.remedyDecisionId(), assessment.disposition(), resolution.finalBreachingRole());
    }

    /** issueType is null for nobody-flagged cases; here it is always the case's own flag type. */
    private void attach(BreachCase breachCase, FlagIssueType issueType, Milestone milestone, InspectionReport report,
                        QualityAssessment assessment) {
        QualityDisposition disposition = assessment.disposition();
        BreachCaseComputedResolution resolution = resolutionOf(issueType, milestone, report, assessment);
        breachCases.update(breachCase.attachComputedResolution(resolution));
        log.info("event=computed_resolution_attached breachCaseId={} reportId={} issueType={} disposition={}"
                        + " prevailingRole={} sellerEntitlement={} buyerRefund={}", breachCase.breachCaseId(),
                report.reportId(), issueType, disposition, resolution.prevailingRole(),
                assessment.finalSellerEntitlement().wire(), assessment.buyerRefundAmount().wire());
    }

    private BreachCaseComputedResolution resolutionOf(FlagIssueType issueType, Milestone milestone,
                                                      InspectionReport report, QualityAssessment assessment) {
        QualityDisposition disposition = assessment.disposition();
        SignerRole prevailing = PrevailingRoleRule.prevailing(disposition, issueType, report.acceptedQuantityKg(),
                milestone.sellerWeighing().declaredWeight(), milestone.buyerReceipt().receivedWeight());
        return new BreachCaseComputedResolution(report.reportId(), issueType,
                report.acceptedQuantityKg(),
                com.agricontract.contract.domain.model.breach.QualityDisposition.valueOf(disposition.name()),
                PrevailingRoleRule.finalBreachingRole(disposition), assessment.finalSellerEntitlement(),
                assessment.buyerRefundAmount(), prevailing);
    }

    private void settle(Contract contract, Milestone milestone, InspectionReport report, QualityAssessment assessment,
                        Instant now) {
        MilestoneSettlement settlement = milestone.settleInspectedConforming(report.acceptedQuantityKg(),
                assessment.finalSellerEntitlement(), assessment.buyerRefundAmount(), now);
        milestones.update(milestone);
        events.milestoneSettled(contract, milestone);
        log.info("event=milestone_settled contractId={} milestoneId={} path={} sellerAmount={} buyerRefundAmount={}",
                contract.getContractId().value(), milestone.milestoneId(), settlement.path(),
                settlement.sellerAmount().wire(), settlement.buyerRefundAmount().wire());
        completion.afterSettled(contract, now);
    }

    /**
     * D85, D91: why this report was asked for, or null when the milestone takes no more. The report a contest asked
     * for (case RESOLVED and CONTESTED, none stored yet); else the first one; else the one re-run after an
     * INCONCLUSIVE first report. The re-run never uses up the contest, so a milestone has at most three reports.
     */
    private InspectionReportPurpose purposeOf(BreachCase breachCase, Milestone milestone) {
        String milestoneId = milestone.milestoneId();
        if (breachCase != null && breachCase.status() == BreachCaseStatus.RESOLVED
                && breachCase.contestStatus() == ContestStatus.CONTESTED) {
            return reports.existsByMilestoneIdAndPurpose(milestoneId, InspectionReportPurpose.CONTEST)
                    ? null : InspectionReportPurpose.CONTEST;
        }
        int stored = reports.countByMilestoneId(milestoneId);
        if (stored == 0) {
            return InspectionReportPurpose.INITIAL;
        }
        boolean firstInconclusive = stored == 1 && reports.findByMilestoneId(milestoneId).orElseThrow()
                .measurementStatus() == MeasurementStatus.INCONCLUSIVE;
        return firstInconclusive ? InspectionReportPurpose.RERUN : null;
    }

    private static void ignore(InspectionReport report, String reason) {
        log.warn("event=inspection_report_ignored reason={} reportId={} contractId={} milestoneId={} eventId={}",
                reason, report.reportId(), report.contractId(), report.milestoneId(), report.receivedEventId());
    }
}
