package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachCaseComputedResolution;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.dispute.DisputeExitStage;
import com.agricontract.contract.domain.model.dispute.DisputeExitType;
import com.agricontract.contract.domain.model.dispute.DisputeExitWindow;
import com.agricontract.contract.domain.model.dispute.InspectionFeeRule;
import com.agricontract.contract.domain.model.inspection.Level15Assignment;
import com.agricontract.contract.domain.model.inspection.Level15Request;
import com.agricontract.contract.domain.model.inspection.InspectionReportPurpose;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.remedy.InspectionFeeParty;
import com.agricontract.contract.domain.model.remedy.RemedyCalculator;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.InspectionReportRepository;
import com.agricontract.contract.domain.repository.Level15AssignmentRepository;
import com.agricontract.contract.domain.repository.Level15RequestRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import com.agricontract.contract.domain.repository.SettlementProposalRepository;
import com.agricontract.contract.domain.model.remedy.RemedyFundType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ending a milestone dispute by a concession or an agreed settlement (D57, D92), shared by the dispute exit use cases
 * and the read model. {@link #lock} takes the row locks in the contract-service order (contract first, by the caller;
 * then breach case, milestone, remedy decision) and assesses the exit window; {@link #end} does the whole exit in the
 * caller's transaction: AttributionDecision (MUTUAL) → RemedyDecision (FINALIZED at once) → milestone SETTLED →
 * remedy.finalized, milestone.dispute_resolved for a concession, inspection.level1_5_cancelled when a request is
 * pending. Never milestone.settled, never a contest window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeExitStep {
    public static final String CANCELLED_BY_CONCESSION = "DISPUTE_CONCEDED";
    public static final String CANCELLED_BY_SETTLEMENT = "DISPUTE_SETTLED";

    private final MilestoneRepository milestones;
    private final BreachCaseRepository breachCases;
    private final RemedyDecisionRepository remedyDecisions;
    private final ContractCompletion completion;
    private final InspectionReportRepository reports;
    private final SettlementProposalRepository proposals;
    private final Level15RequestRepository requests;
    private final Level15AssignmentRepository assignments;
    private final MilestoneContestStep contestStep;
    private final Level15Inspections level15;
    private final ContractEventPublisher events;
    private final TransactionRunner transactions;

    /** The dispute of one milestone under its row locks, and its exit window (empty: no exit now). */
    public record Locked(Contract contract, Milestone milestone, BreachCase breachCase, RemedyDecision latest,
                         Optional<DisputeExitWindow> window) {
    }

    /**
     * Locks the case (if any), the milestone and the case's latest decision, after the contract lock the caller holds.
     * The milestone is read first without a lock to find its case: every writer of the flag holds the contract lock,
     * so it cannot change in between. Empty when the milestone is not the contract's.
     */
    public Optional<Locked> lock(Contract contract, String milestoneId) {
        transactions.requireTransaction();
        Optional<Milestone> peek = milestones.findById(milestoneId)
                .filter(m -> m.contractId().equals(contract.getContractId().value()));
        if (peek.isEmpty()) {
            return Optional.empty();
        }
        String caseId = peek.get().flag() == null ? null : peek.get().flag().breachCaseId();
        BreachCase breachCase = caseId == null ? null : breachCases.findByIdForUpdate(caseId).orElse(null);
        Milestone milestone = milestones.findByIdForUpdate(milestoneId).orElseThrow();
        RemedyDecision latest = breachCase == null ? null : remedyDecisions.findByBreachCaseId(caseId)
                .flatMap(d -> remedyDecisions.findByIdForUpdate(d.remedyDecisionId())).orElse(null);
        return Optional.of(new Locked(contract, milestone, breachCase, latest,
                windowOf(contract, milestone, breachCase, latest)));
    }

    /** True when the milestone exists and belongs to the contract (plain read). */
    public boolean belongsTo(Contract contract, String milestoneId) {
        return milestones.findById(milestoneId).filter(m -> m.contractId().equals(contract.getContractId().value()))
                .isPresent();
    }

    /** The exit window without locks, for the read model. */
    public Optional<DisputeExitWindow> peek(Contract contract, Milestone milestone) {
        String caseId = milestone.flag() == null ? null : milestone.flag().breachCaseId();
        BreachCase breachCase = caseId == null ? null : breachCases.findById(caseId).orElse(null);
        RemedyDecision latest = caseId == null ? null : remedyDecisions.findByBreachCaseId(caseId).orElse(null);
        return windowOf(contract, milestone, breachCase, latest);
    }

    private Optional<DisputeExitWindow> windowOf(Contract contract, Milestone milestone, BreachCase breachCase,
                                                 RemedyDecision latest) {
        if (contract.getStatus() != ContractStatus.ACTIVE || (milestone.status() != MilestoneStatus.CONTESTED
                && milestone.status() != MilestoneStatus.AWAITING_SELLER_RESPONSE)) {
            return Optional.empty();
        }
        Map<InspectionReportPurpose, com.agricontract.contract.domain.model.quality.MeasurementStatus> statuses =
                reports.statusesByPurpose(milestone.milestoneId());
        return DisputeExitWindow.assess(milestone, breachCase, latest, statuses,
                MilestoneCleanSettlement.requiresInspection(contract));
    }

    /**
     * Ends the dispute on the accepted position. conceder is the conceding party for a concession, null for a
     * settlement; agreedAmount is the settlement's seller entitlement, null for a concession. The caller already
     * checked the window allows this exit, and for a settlement accepted the open proposal.
     */
    public RemedyDecision end(Locked locked, DisputeExitWindow window, DisputeExitType type, SignerRole conceder,
                              VndAmount agreedAmount, String actorId, Instant now) {
        transactions.requireTransaction();
        Contract contract = locked.contract();
        Milestone milestone = locked.milestone();
        BreachCaseComputedResolution agreed = type == DisputeExitType.CONCEDE ? window.concession(conceder)
                : window.settlement(agreedAmount);
        InspectionFeeParty fee = feeOf(contract, milestone, window, type, conceder, now);

        BreachCase breachCase = locked.breachCase();
        if (breachCase == null) {
            breachCase = contestStep.openForExit(contract, milestone, now);
        }
        if (window.stage() == DisputeExitStage.CONTESTED_DECISION) {
            remedyDecisions.markSuperseded(locked.latest().remedyDecisionId(), now);
        }
        BreachCase resolved = breachCase.endByAgreement(agreed, now, actorId);
        breachCases.update(resolved);

        var terms = contract.getCoffeeTerms().snapshot();
        BigDecimal left = terms.sellerDepositAmount().value()
                .subtract(remedyDecisions.depositForfeited(contract.getContractId().value(),
                        RemedyFundType.SELLER_DEPOSIT).value());
        VndAmount remaining = new VndAmount(left.signum() < 0 ? BigDecimal.ZERO : left);
        int settled = (int) milestones.findByContractId(milestone.contractId()).stream()
                .filter(m -> m.status() == MilestoneStatus.SETTLED).count();
        RemedyDecision decision = new RemedyCalculator(() -> UUID.randomUUID().toString())
                .calculateAgreed(resolved, milestone, terms, remaining, settled, fee, now);
        remedyDecisions.insert(decision);

        BigDecimal billableKg = agreed.qualityDisposition() == QualityDisposition.NON_CONFORMING ? BigDecimal.ZERO
                : agreed.acceptedQuantityKg();
        milestone.settleByAgreement(resolved.breachCaseId(), billableKg, decision.sellerPayment(),
                decision.buyerRefund(), now);
        milestones.update(milestone);
        events.remedyFinalized(contract, decision, now);
        events.remedyFinalizedRequested(contract, decision, now);
        if (type == DisputeExitType.CONCEDE) {
            events.milestoneDisputeResolved(contract, milestone, agreed.prevailingRole());
            proposals.lapseOpen(milestone.milestoneId(), now);
        }
        boolean cancelled = level15.cancelOpen(contract, milestone,
                type == DisputeExitType.CONCEDE ? CANCELLED_BY_CONCESSION : CANCELLED_BY_SETTLEMENT, now);
        completion.afterSettled(contract, now);
        log.info("event=dispute_exit contractId={} milestoneId={} type={} stage={} conceder={} breachCaseId={}"
                        + " remedyDecisionId={} finalBreachingRole={} sellerAmount={} feeParty={} inspectionCancelled={}",
                contract.getContractId().value(), milestone.milestoneId(), type, window.stage(), conceder,
                resolved.breachCaseId(), decision.remedyDecisionId(), decision.finalBreachingRole(),
                decision.sellerPayment().wire(), fee, cancelled);
        return decision;
    }

    private InspectionFeeParty feeOf(Contract contract, Milestone milestone, DisputeExitWindow window,
                                     DisputeExitType type, SignerRole conceder, Instant now) {
        Level15Request open = requests.findLatestOpen(milestone.milestoneId()).orElse(null);
        Level15Assignment assignment = assignments.findLatest(milestone.milestoneId()).orElse(null);
        // A request the system made (a silent QUALITY flag contested automatically, D82) is put on the seller.
        SignerRole requester = open == null || open.initiatedByActorId() == null ? SignerRole.SELLER
                : contract.roleOf(open.initiatedByActorId()).orElse(SignerRole.SELLER);
        return InspectionFeeRule.forExit(type, conceder, window, open, assignment, requester, now);
    }
}
