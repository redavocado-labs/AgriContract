package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.LockResultCommand;
import com.agricontract.escrow.application.port.MilestoneFundingEventPublisher;
import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.model.RetrySchedule;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Applies one bank result to a milestone funding (#120, D69), under the milestone's row lock:
 * <ul>
 *   <li>Locked: LOCKED and escrow.milestone_funded in the same transaction, once (a duplicate result changes
 *       nothing). Also after the retries ran out: the bank ledger holds the money, so the milestone is funded.</li>
 *   <li>Failed: counted and the same request resent per the RetrySchedule (a missing balance too, D69). The failure
 *       after the last retry fails the funding and writes escrow.milestone_funding_failed with the bank's reason.
 *       A late failure for a LOCKED funding, or a repeated one while no request is in flight, is ignored.</li>
 * </ul>
 * A result that matches no funding, or not the request it names, is refused (DLQ): a producer bug, not a retry case.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordMilestoneFundingResultUseCaseImpl implements RecordMilestoneFundingResultUseCase {

    private final EscrowMilestoneRepository milestones;
    private final MilestoneFundingEventPublisher events;
    private final RetrySchedule retrySchedule;
    private final Clock clock;

    @Override
    public void execute(LockResultCommand command) {
        EscrowMilestone milestone = milestones.findByIdForUpdate(command.milestoneId())
                .orElseThrow(() -> new InvalidDepositLegException("No funding of milestone " + command.milestoneId()
                        + " for result " + command.eventId()));
        requireSameRequest(milestone, command);
        Instant now = clock.instant();

        if (command.locked()) {
            EscrowMilestone.LockOutcome outcome = milestone.markLocked(command.ledgerEntryId(), command.occurredAt(),
                    now);
            if (outcome == EscrowMilestone.LockOutcome.DUPLICATE) {
                log.info("event=milestone_lock_result_duplicate milestoneId={} sourceEventId={}",
                        command.milestoneId(), command.sourceEventId());
                return;
            }
            milestones.update(milestone);
            events.milestoneFunded(milestone, command.correlationId(), command.eventId());
            if (outcome == EscrowMilestone.LockOutcome.LOCKED_AFTER_FAILURE) {
                log.warn("event=milestone_locked_after_funding_failed contractId={} milestoneId={} ledgerEntryId={}",
                        milestone.getContractId(), milestone.getMilestoneId(), command.ledgerEntryId());
            }
            log.info("event=milestone_funded contractId={} milestoneId={} ledgerEntryId={} lockedAt={}",
                    milestone.getContractId(), milestone.getMilestoneId(), command.ledgerEntryId(),
                    milestone.getLockedAt());
            return;
        }

        EscrowMilestone.FailureOutcome outcome = milestone.markFailed(command.failureReason(), now, retrySchedule);
        switch (outcome) {
            case IGNORED_ALREADY_LOCKED -> log.warn("event=milestone_lock_late_failure_ignored milestoneId={}"
                    + " sourceEventId={} reason={}", command.milestoneId(), command.sourceEventId(),
                    command.failureReason());
            case IGNORED_NOT_IN_FLIGHT -> log.info("event=milestone_lock_failure_repeated milestoneId={}"
                    + " status={} eventId={}", command.milestoneId(), milestone.getStatus(), command.eventId());
            case RETRY_SCHEDULED -> {
                milestones.update(milestone);
                log.warn("event=milestone_lock_failed contractId={} milestoneId={} failures={} nextAttemptAt={}"
                                + " reason={}", milestone.getContractId(), milestone.getMilestoneId(),
                        milestone.getFailedAttempts(), milestone.getNextAttemptAt(), command.failureReason());
            }
            case RETRIES_EXHAUSTED -> {
                milestones.update(milestone);
                events.milestoneFundingFailed(milestone, command.correlationId(), command.eventId());
                log.warn("event=milestone_funding_failed contractId={} milestoneId={} failures={} reason={}",
                        milestone.getContractId(), milestone.getMilestoneId(), milestone.getFailedAttempts(),
                        command.failureReason());
            }
        }
    }

    private static void requireSameRequest(EscrowMilestone milestone, LockResultCommand command) {
        if (!milestone.getRequestEventId().equals(command.sourceEventId())
                || !milestone.getContractId().equals(command.contractId())
                || !milestone.getBuyerId().equals(command.userId())
                || milestone.getBatchAmount().amount().compareTo(command.amount()) != 0) {
            throw new InvalidDepositLegException("Result " + command.eventId() + " does not match the funding of"
                    + " milestone " + milestone.getMilestoneId() + " (request, contract, buyer or amount)");
        }
    }
}
