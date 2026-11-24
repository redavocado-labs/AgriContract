package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.remedy.RemedyCalculator;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyFundType;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * #238, D94: the normal completion of a contract. Every path that settles a milestone calls {@link #afterSettled}
 * right after, in its own transaction and under the contract row lock it already holds. When that was the last
 * milestone, the SYSTEM no-fault decision returning what is left of both deposits is stored, remedy.finalized is
 * written to the outbox, and the contract waits ACTIVE for escrow.contract_lock_cleared to name that decision (D93).
 * contract.settled is written only then, by CommitTerminalAfterLockClearedUseCase.
 *
 * <p>The contract row lock makes two last milestones settling together run one after the other, so the second sees
 * every milestone SETTLED and the first does not; a contract that already waits for a decision is never given a second
 * one, and V28 allows one contract-level decision per contract.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractCompletion {
    private final ContractRepository contracts;
    private final MilestoneRepository milestones;
    private final RemedyDecisionRepository remedyDecisions;
    private final ContractEventPublisher events;
    private final TransactionRunner transactions;

    /**
     * @param contract the contract of the milestone just settled, loaded under its row lock
     * @return the completion decision when this settle completed the contract, else null
     */
    public RemedyDecision afterSettled(Contract contract, Instant now) {
        transactions.requireTransaction();
        String contractId = contract.getContractId().value();
        if (contract.getStatus() != ContractStatus.ACTIVE || contract.getPendingTerminalDecisionId() != null) {
            return null;
        }
        List<Milestone> all = milestones.findByContractId(contractId);
        if (all.isEmpty() || all.stream().anyMatch(m -> m.status() != MilestoneStatus.SETTLED)) {
            return null;
        }
        RemedyDecision decision = new RemedyCalculator(() -> UUID.randomUUID().toString()).normalCompletion(
                contractId, contract.getCoffeeTerms().snapshot(),
                remedyDecisions.depositForfeited(contractId, RemedyFundType.BUYER_DEPOSIT),
                remedyDecisions.depositForfeited(contractId, RemedyFundType.SELLER_DEPOSIT), all.size(), now);
        remedyDecisions.insert(decision);
        events.remedyFinalized(contract, decision, now);
        contract.awaitTerminal(decision.remedyDecisionId(), ContractStatus.SETTLED);
        contracts.save(contract);
        log.info("event=contract_completion_decided contractId={} remedyDecisionId={} milestones={} legs={}",
                contractId, decision.remedyDecisionId(), all.size(), decision.legs().size());
        return decision;
    }
}
