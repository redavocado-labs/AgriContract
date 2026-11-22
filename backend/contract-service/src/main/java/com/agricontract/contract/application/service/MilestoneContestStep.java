package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.breach.BreachReasonCode;
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
        events.inspectionLevel15Requested(contract, milestone, actorId, now);
        log.info("event=milestone_contested contractId={} milestoneId={} by={} breachCaseId={}",
                contract.getContractId().value(), milestone.milestoneId(), by, breachCase.breachCaseId());
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
