package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachReasonCode;
import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.milestone.BuyerReceipt;
import com.agricontract.contract.domain.model.milestone.ContestedBy;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.SellerWeighing;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The contest step shared by the seller's respond and the seller-silence job for a QUALITY flag (D80, D82). Runs inside
 * the caller's transaction, under the contract and milestone row locks: the milestone becomes CONTESTED, a BreachCase
 * is opened as an allegation only (its evidence is the evidence files the milestone holds), and breach.reported and
 * inspection.level1_5_requested are written to the outbox, each exactly once because a second contest is refused by
 * the milestone. No money or reputation consequence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilestoneContestStep {
    private final MilestoneRepository milestones;
    private final BreachCaseRepository breachCases;
    private final ContractEventPublisher events;
    private final Level15Inspections level15;
    private final TransactionRunner transactions;

    /** actorId is the contesting seller, null when the system contests a silent QUALITY flag. */
    public BreachCase contest(Contract contract, Milestone milestone, ContestedBy by, String actorId, Instant now) {
        transactions.requireTransaction();
        BreachCase breachCase = BreachCase.reportedByBuyer(UUID.randomUUID().toString(),
                contract.getContractId().value(), milestone.milestoneId(), reasonOf(milestone),
                evidenceOf(milestone), now);
        milestone.contest(by, breachCase.breachCaseId(), now);
        breachCases.insert(breachCase);
        milestones.update(milestone);
        events.breachReported(breachCase);
        level15.request(contract, milestone, InspectionRequestReason.DISPUTE_ROUTED, actorId, now);
        log.info("event=milestone_contested contractId={} milestoneId={} by={} breachCaseId={}",
                contract.getContractId().value(), milestone.milestoneId(), by, breachCase.breachCaseId());
        return breachCase;
    }

    /**
     * D77: a mandatory inspection report found the goods PARTIALLY or NON conforming on a milestone nobody flagged. The
     * system opens the BreachCase (allegation, QUALITY_BELOW_COMMITTED) and the milestone becomes CONTESTED;
     * breach.reported is written. The inspection is already done, so level1_5_requested is not written again.
     */
    public BreachCase openByInspection(Contract contract, Milestone milestone, Instant now) {
        transactions.requireTransaction();
        BreachCase breachCase = BreachCase.openedBySystem(UUID.randomUUID().toString(),
                contract.getContractId().value(), milestone.milestoneId(), BreachReasonCode.QUALITY_BELOW_COMMITTED,
                evidenceOf(milestone), now);
        milestone.contestByInspection(breachCase.breachCaseId(), now);
        breachCases.insert(breachCase);
        milestones.update(milestone);
        events.breachReported(breachCase);
        log.info("event=milestone_contested contractId={} milestoneId={} by=INSPECTION breachCaseId={}",
                contract.getContractId().value(), milestone.milestoneId(), breachCase.breachCaseId());
        return breachCase;
    }

    /**
     * D92: a flag still waiting for the seller's response ends by a concession. The BreachCase is opened here exactly
     * as the contest step opens it (allegation by the buyer, breach.reported written), but the milestone is not
     * contested and no inspection is asked for: the caller resolves the case in the same transaction.
     */
    public BreachCase openForExit(Contract contract, Milestone milestone, Instant now) {
        transactions.requireTransaction();
        BreachCase breachCase = BreachCase.reportedByBuyer(UUID.randomUUID().toString(),
                contract.getContractId().value(), milestone.milestoneId(), reasonOf(milestone),
                evidenceOf(milestone), now);
        breachCases.insert(breachCase);
        events.breachReported(breachCase);
        return breachCase;
    }

    private static BreachReasonCode reasonOf(Milestone milestone) {
        return milestone.flag() != null && milestone.flag().issueType() == FlagIssueType.QUALITY
                ? BreachReasonCode.QUALITY_BELOW_COMMITTED : BreachReasonCode.SHORTFALL;
    }

    /** The seller's and the buyer's evidence and certificate files, those that exist. */
    private static List<String> evidenceOf(Milestone milestone) {
        List<String> ids = new ArrayList<>();
        SellerWeighing seller = milestone.sellerWeighing();
        BuyerReceipt buyer = milestone.buyerReceipt();
        if (seller != null) {
            add(ids, seller.evidenceFileId());
            add(ids, seller.deliveryCertificateFileId());
        }
        if (buyer != null) {
            add(ids, buyer.evidenceFileId());
            add(ids, buyer.certificateFileId());
        }
        return ids;
    }

    private static void add(List<String> ids, String fileId) {
        if (fileId != null && !ids.contains(fileId)) {
            ids.add(fileId);
        }
    }
}
