package com.agricontract.contract.application.usecase;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.dto.MilestoneFundingResultCommand;
import com.agricontract.contract.application.exception.MilestoneFundingRefusedException;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.FundingStatusMail;
import com.agricontract.contract.application.port.MilestoneNotificationSettings;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.InvalidMilestoneTransitionException;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * escrow.milestone_funded / escrow.milestone_funding_failed (D64). Runs in the consumer's transaction, under the
 * milestone row lock.
 * <ul>
 *   <li>Funded: LOCKED, the milestone moves CREATED → IN_PROGRESS and fundingDelayBusinessDays is counted from the
 *       request to the lock on the Vietnam calendar, for every milestone (the delivery deadline moves by it).</li>
 *   <li>Failed: FUNDING_FAILED with the reason, and the FUNDING_FAILED mail to buyer, seller and operator (D96). The
 *       cure window and the buyer breach are #239.</li>
 *   <li>The same result again: nothing to do.</li>
 *   <li>Unknown milestone, another contract's milestone, or a state that does not allow it: refused to the DLQ.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordMilestoneFundingResultUseCase {
    private final MilestoneRepository milestones;
    private final ContractRepository contracts;
    private final ContractEventPublisher events;
    private final MilestoneNotificationSettings notifications;
    private final VietnamBusinessDays calendar;
    private final Clock clock;

    public void funded(MilestoneFundingResultCommand command) {
        Milestone milestone = load(command);
        try {
            int delay = milestone.fundingRequestedAt() == null ? 0
                    : calendar.businessDaysBetween(milestone.fundingRequestedAt(), command.lockedAt());
            if (!milestone.recordFunded(command.lockedAt(), delay)) {
                log.info("event=milestone_funded_duplicate milestoneId={} eventId={}", command.milestoneId(),
                        command.eventId());
                return;
            }
            milestones.update(milestone);
            log.info("event=milestone_funded contractId={} milestoneId={} fundingDelayBusinessDays={}",
                    command.contractId(), command.milestoneId(), delay);
        } catch (InvalidMilestoneTransitionException | IllegalArgumentException e) {
            throw new MilestoneFundingRefusedException("escrow.milestone_funded " + command.eventId() + ": "
                    + e.getMessage(), e);
        }
    }

    public void failed(MilestoneFundingResultCommand command) {
        Milestone milestone = load(command);
        try {
            if (!milestone.recordFundingFailed(command.reason())) {
                log.info("event=milestone_funding_failed_duplicate milestoneId={} eventId={}",
                        command.milestoneId(), command.eventId());
                return;
            }
            milestones.update(milestone);
            // D96: buyer, seller and the operator are told, once (a duplicate returned above).
            Contract contract = contracts.findById(new ContractId(milestone.contractId()))
                    .orElseThrow(() -> new MilestoneFundingRefusedException("Funding result " + command.eventId()
                            + " for a milestone of an unknown contract " + milestone.contractId()));
            events.milestoneFundingStatusRequested(contract, milestone, FundingStatusMail.FUNDING_FAILED, null,
                    command.reason(), notifications.operatorEmail(),
                    Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
            log.warn("event=milestone_funding_failed contractId={} milestoneId={}", command.contractId(),
                    command.milestoneId());
        } catch (InvalidMilestoneTransitionException e) {
            throw new MilestoneFundingRefusedException("escrow.milestone_funding_failed " + command.eventId() + ": "
                    + e.getMessage(), e);
        }
    }

    private Milestone load(MilestoneFundingResultCommand command) {
        Milestone milestone = milestones.findByIdForUpdate(command.milestoneId())
                .orElseThrow(() -> new MilestoneFundingRefusedException("Funding result " + command.eventId()
                        + " for unknown milestone " + command.milestoneId()));
        if (!milestone.contractId().equals(command.contractId())) {
            throw new MilestoneFundingRefusedException("Funding result " + command.eventId() + ": milestone "
                    + command.milestoneId() + " does not belong to contract " + command.contractId());
        }
        return milestone;
    }
}
