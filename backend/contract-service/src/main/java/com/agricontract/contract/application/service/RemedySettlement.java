package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCaseComputedResolution;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What happens when a RemedyDecision is FINALIZED (#137): the contested milestone becomes SETTLED on the money of the
 * decision's MILESTONE_PAYMENT legs, then remedy.finalized and milestone.dispute_resolved are written, in that order,
 * to the outbox. milestone.settled is never written on this path. A last milestone completes the contract (#238), which
 * stays ACTIVE until its lock is cleared. Runs inside
 * the caller's transaction, under the contract, case and milestone row locks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemedySettlement {
    private final MilestoneRepository milestones;
    private final ContractEventPublisher events;
    private final TransactionRunner transactions;
    private final ContractCompletion completion;

    public void settleAndPublish(Contract contract, RemedyDecision finalized, Milestone milestone,
                                 BreachCaseComputedResolution computed, Instant now) {
        transactions.requireTransaction();
        if (finalized.status() != RemedyStatus.FINALIZED) {
            throw new IllegalStateException("Remedy decision " + finalized.remedyDecisionId() + " is not FINALIZED");
        }
        BigDecimal billableKg = computed.qualityDisposition() == QualityDisposition.NON_CONFORMING
                ? BigDecimal.ZERO : computed.acceptedQuantityKg();
        milestone.settleDisputeResolved(billableKg, finalized.sellerPayment(), finalized.buyerRefund(), now);
        milestones.update(milestone);
        events.remedyFinalized(contract, finalized, now);
        events.milestoneDisputeResolved(contract, milestone, computed.prevailingRole());
        log.info("event=remedy_finalized contractId={} remedyDecisionId={} milestoneId={} finalBreachingRole={}"
                        + " legs={}", contract.getContractId().value(), finalized.remedyDecisionId(),
                milestone.milestoneId(), finalized.finalBreachingRole(), finalized.legs().size());
        completion.afterSettled(contract, now);
    }
}
