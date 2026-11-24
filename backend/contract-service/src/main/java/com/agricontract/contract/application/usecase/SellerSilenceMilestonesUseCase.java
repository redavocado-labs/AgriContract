package com.agricontract.contract.application.usecase;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.MilestoneFlagSettings;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.application.service.ContractCompletion;
import com.agricontract.contract.application.service.MilestoneContestStep;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.ContestedBy;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneSettlement;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.application.port.MilestoneStatusMail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * D82: acts on an AWAITING_SELLER_RESPONSE milestone whose response window (sellerResponseWindowDays business days from
 * the flag) has passed. A QUALITY flag is contested by the system (the contest step, no consent); a QUANTITY flag
 * settles on the buyer's weight (SELLER_SILENT_QUANTITY) with milestone.settled and milestone.dispute_resolved
 * favouring the buyer. Each milestone is re-checked under the contract and milestone row locks in its own transaction
 * (the seller may have responded meanwhile), so it is handled once, and one failing milestone does not stop the next.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerSilenceMilestonesUseCase {
    private static final int BATCH = 100;

    private final MilestoneRepository milestones;
    private final ContractRepository contracts;
    private final MilestoneContestStep contestStep;
    private final ContractCompletion completion;
    private final ContractEventPublisher events;
    private final MilestoneFlagSettings settings;
    private final VietnamBusinessDays calendar;
    private final TransactionRunner transactions;
    private final Clock clock;

    /** Returns how many milestones were handled. */
    public int execute() {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<String> candidates = milestones.findIdsAwaitingSellerResponseOfActiveContracts(BATCH);
        int handled = 0;
        for (String milestoneId : candidates) {
            try {
                if (Boolean.TRUE.equals(transactions.inTransaction(() -> handleOne(milestoneId, now)))) {
                    handled++;
                }
            } catch (RuntimeException e) {
                log.error("event=milestone_seller_silence_failed milestoneId={} reason={}", milestoneId,
                        e.getMessage(), e);
            }
        }
        return handled;
    }

    private boolean handleOne(String milestoneId, Instant now) {
        String contractId = milestones.findContractIdOf(milestoneId).orElse(null);
        if (contractId == null) {
            return false;
        }
        // Contract lock first, as every contract-service write does, then the milestone.
        Contract contract = contracts.findByIdForUpdate(new ContractId(contractId)).orElse(null);
        Milestone milestone = milestones.findByIdForUpdate(milestoneId).orElse(null);
        if (contract == null || milestone == null || contract.getStatus() != ContractStatus.ACTIVE
                || milestone.status() != MilestoneStatus.AWAITING_SELLER_RESPONSE || milestone.flag() == null) {
            return false;
        }
        Instant deadline = calendar.deadline(milestone.flag().flaggedAt(), settings.sellerResponseWindowDays());
        if (deadline.isAfter(now)) {
            return false;
        }
        if (milestone.flag().issueType() == FlagIssueType.QUALITY) {
            contestStep.contest(contract, milestone, ContestedBy.SYSTEM, null, now);
            return true;
        }
        MilestoneSettlement settlement = milestone.settleSellerSilentQuantity(now);
        milestones.update(milestone);
        events.milestoneSettled(contract, milestone);
        events.milestoneDisputeResolvedForBuyer(contract, milestone);
        events.milestoneStatusRequested(contract, milestone, MilestoneStatusMail.CONFIRMED, null, now);
        log.info("event=milestone_settled contractId={} milestoneId={} path={} sellerAmount={} buyerRefundAmount={}",
                contractId, milestoneId, settlement.path(), settlement.sellerAmount().wire(),
                settlement.buyerRefundAmount().wire());
        completion.afterSettled(contract, now);
        return true;
    }
}
