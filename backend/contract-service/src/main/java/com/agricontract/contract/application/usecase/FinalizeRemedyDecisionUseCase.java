package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.RemedySettlement;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * PENDING_CONTEST becomes FINALIZED (#137): the contest window closed with no contest (#241 calls this). Lock order
 * contract, case, milestone. The milestone is settled and remedy.finalized and milestone.dispute_resolved are written
 * in the same transaction. A decision that is already FINALIZED is a no-op, so a repeated call writes nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalizeRemedyDecisionUseCase {
    private final ContractRepository contracts;
    private final BreachCaseRepository breachCases;
    private final MilestoneRepository milestones;
    private final RemedyDecisionRepository remedyDecisions;
    private final RemedySettlement settlement;
    private final TransactionRunner transactions;
    private final Clock clock;

    public void execute(String remedyDecisionId) {
        transactions.inTransaction(() -> finalizeDecision(remedyDecisionId));
    }

    private void finalizeDecision(String remedyDecisionId) {
        RemedyDecision peek = remedyDecisions.findById(remedyDecisionId)
                .orElseThrow(() -> new IllegalArgumentException("Remedy decision " + remedyDecisionId + " not found"));
        if (peek.status() == RemedyStatus.FINALIZED) {
            return;
        }
        Contract contract = contracts.findByIdForUpdate(new ContractId(peek.contractId()))
                .orElseThrow(() -> new ContractNotFoundException(peek.contractId()));
        BreachCase breachCase = breachCases.findByIdForUpdate(peek.breachCaseId())
                .orElseThrow(() -> new IllegalStateException("Breach case " + peek.breachCaseId() + " not found"));
        Milestone milestone = milestones.findByIdForUpdate(peek.milestoneId())
                .orElseThrow(() -> new IllegalStateException("Milestone " + peek.milestoneId() + " not found"));
        RemedyDecision decision = remedyDecisions.findByIdForUpdate(remedyDecisionId).orElseThrow();
        if (decision.status() == RemedyStatus.FINALIZED) {
            return;
        }
        if (breachCase.computedResolution() == null) {
            throw new IllegalStateException("Breach case " + breachCase.breachCaseId() + " has no computed resolution");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        RemedyDecision finalized = decision.finalizedAt(now);
        remedyDecisions.markFinalized(finalized);
        settlement.settleAndPublish(contract, finalized, milestone, breachCase.computedResolution(), now);
    }
}
