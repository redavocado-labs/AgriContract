package com.agricontract.contract.application.usecase;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.MilestoneCleanSettlement;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.milestone.SettlementPath;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * D78: settles a BUYER_RECEIVED milestone whose confirm window (buyerConfirmWindowDays business days from the receipt)
 * has passed, as CLEAN_AUTO_CONFIRMED with confirmedBy SYSTEM_TIMEOUT. Each milestone is re-checked under the contract
 * and milestone row locks in its own transaction (the buyer may have confirmed meanwhile), so it settles once, and one
 * failing milestone does not stop the next. A contract that requires an inspection report before settlement is
 * skipped. No consent: nobody consented.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoConfirmMilestonesUseCase {
    private static final int BATCH = 100;

    private final MilestoneRepository milestones;
    private final ContractRepository contracts;
    private final MilestoneCleanSettlement settlement;
    private final VietnamBusinessDays calendar;
    private final TransactionRunner transactions;
    private final Clock clock;

    /** Returns how many milestones were settled. */
    public int execute() {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<String> candidates = milestones.findIdsBuyerReceivedOfActiveContracts(BATCH);
        int settled = 0;
        for (String milestoneId : candidates) {
            try {
                if (Boolean.TRUE.equals(transactions.inTransaction(() -> confirmOne(milestoneId, now)))) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.error("event=milestone_auto_confirm_failed milestoneId={} reason={}", milestoneId, e.getMessage(),
                        e);
            }
        }
        return settled;
    }

    private boolean confirmOne(String milestoneId, Instant now) {
        String contractId = milestones.findContractIdOf(milestoneId).orElse(null);
        if (contractId == null) {
            return false;
        }
        // Contract lock first, as every contract-service write does, then the milestone.
        Contract contract = contracts.findByIdForUpdate(new ContractId(contractId)).orElse(null);
        Milestone milestone = milestones.findByIdForUpdate(milestoneId).orElse(null);
        if (contract == null || milestone == null || contract.getStatus() != ContractStatus.ACTIVE
                || milestone.status() != MilestoneStatus.BUYER_RECEIVED || milestone.buyerReceipt() == null) {
            return false;
        }
        if (MilestoneCleanSettlement.inspectionPending(contract)) {
            return false;
        }
        int days = contract.getCoffeeTerms().snapshot().negotiated().buyerConfirmWindowDays();
        Instant deadline = calendar.deadline(milestone.buyerReceipt().receivedAt(), days);
        if (deadline.isAfter(now)) {
            return false;
        }
        settlement.settle(contract, milestone, SettlementPath.CLEAN_AUTO_CONFIRMED, now);
        return true;
    }
}
