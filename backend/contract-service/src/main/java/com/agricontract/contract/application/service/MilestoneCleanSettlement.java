package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneSettlement;
import com.agricontract.contract.domain.model.milestone.SettlementPath;
import com.agricontract.contract.domain.model.terms.InspectionRequirement;
import com.agricontract.contract.domain.repository.InspectionReportRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The settle step shared by the buyer's confirm and the auto-confirm job (D77, D78). Runs inside the caller's
 * transaction, under the contract and milestone row locks: the milestone becomes SETTLED with its settlement, and
 * milestone.settled and milestone.buyer_confirmed are written to the outbox, each exactly once because a second settle
 * is refused by the milestone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilestoneCleanSettlement {
    private final MilestoneRepository milestones;
    private final InspectionReportRepository inspectionReports;
    private final ContractEventPublisher events;
    private final TransactionRunner transactions;

    /** True when the contract requires an inspection report before the milestones settle (D77). */
    public static boolean requiresInspection(Contract contract) {
        return contract.getCoffeeTerms().snapshot().negotiated().inspectionTerms().requirement()
                == InspectionRequirement.MANDATORY_BEFORE_SETTLEMENT;
    }

    /** True when the contract requires an inspection report and none is confirmed for this milestone. */
    public boolean inspectionPending(Contract contract, Milestone milestone) {
        return requiresInspection(contract) && !inspectionReports.existsCompleteByMilestoneId(milestone.milestoneId());
    }

    /** 409 INSPECTION_REPORT_PENDING when {@link #inspectionPending}. */
    public void requireNoPendingInspection(Contract contract, Milestone milestone) {
        if (inspectionPending(contract, milestone)) {
            throw new ContractConflictException(ContractConflictException.INSPECTION_REPORT_PENDING,
                    "This contract requires an inspection report before settlement; none is confirmed yet.");
        }
    }

    public MilestoneSettlement settle(Contract contract, Milestone milestone, SettlementPath path, Instant now) {
        transactions.requireTransaction();
        MilestoneSettlement settlement = milestone.settleClean(path,
                contract.getCoffeeTerms().snapshot().negotiated().toleranceRate(), now);
        milestones.update(milestone);
        events.milestoneSettled(contract, milestone);
        events.milestoneBuyerConfirmed(contract, milestone,
                path == SettlementPath.CLEAN_CONFIRMED ? "BUYER" : "SYSTEM_TIMEOUT");
        log.info("event=milestone_settled contractId={} milestoneId={} path={} sellerAmount={} buyerRefundAmount={}",
                contract.getContractId().value(), milestone.milestoneId(), path, settlement.sellerAmount().wire(),
                settlement.buyerRefundAmount().wire());
        return settlement;
    }
}
