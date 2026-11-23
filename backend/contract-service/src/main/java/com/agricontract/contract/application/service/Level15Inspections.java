package com.agricontract.contract.application.service;

import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.inspection.InspectionRequestReason;
import com.agricontract.contract.domain.model.inspection.Level15Request;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.repository.Level15RequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The Level 1.5 requests contract-service makes, tracked (D92): every inspection.level1_5_requested is written here
 * with a level1_5_request row, which ends REPORTED when a report for the milestone is stored, or CANCELLED with
 * inspection.level1_5_cancelled when the parties end the dispute first. Runs in the caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Level15Inspections {
    private final Level15RequestRepository requests;
    private final ContractEventPublisher events;
    private final TransactionRunner transactions;

    /** Records the request and writes inspection.level1_5_requested. initiatedByActorId null = the system asks. */
    public void request(Contract contract, Milestone milestone, InspectionRequestReason reason,
                        String initiatedByActorId, Instant now) {
        transactions.requireTransaction();
        requests.insert(Level15Request.open(milestone.milestoneId(), reason, initiatedByActorId, now));
        events.inspectionLevel15Requested(contract, milestone, reason, initiatedByActorId, now);
    }

    /** A report for the milestone was stored: its open requests end REPORTED. */
    public void reported(String milestoneId, Instant now) {
        transactions.requireTransaction();
        requests.endOpen(milestoneId, Level15Request.EndReason.REPORTED, now);
    }

    /**
     * The dispute ended by agreement: the open requests, except a MANDATORY_INSPECTION one, end CANCELLED and inspection.level1_5_cancelled is written,
     * once, only when one was open. Returns whether one was.
     *
     * @param reason DISPUTE_CONCEDED or DISPUTE_SETTLED
     */
    public boolean cancelOpen(Contract contract, Milestone milestone, String reason, Instant now) {
        transactions.requireTransaction();
        // A MANDATORY_INSPECTION request is a signed term: an exit never cancels it (the window refuses such
        // contracts already; this keeps the request even if that check were bypassed).
        int ended = requests.cancelOpenExceptMandatory(milestone.milestoneId(), now);
        if (ended == 0) {
            return false;
        }
        events.inspectionLevel15Cancelled(contract, milestone, reason, now);
        log.info("event=level1_5_request_cancelled contractId={} milestoneId={} reason={} requests={}",
                contract.getContractId().value(), milestone.milestoneId(), reason, ended);
        return true;
    }
}
