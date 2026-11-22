package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.ReturnResultCommand;
import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import com.agricontract.escrow.domain.model.EscrowMilestone;
import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.model.RetrySchedule;
import com.agricontract.escrow.domain.model.SettlementLeg;
import com.agricontract.escrow.domain.repository.EscrowMilestoneRepository;
import com.agricontract.escrow.domain.repository.SettlementLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * A bank result for a milestone settlement leg (#248), under the milestone's row lock then its legs':
 * <ul>
 *   <li>completed: the leg is COMPLETED; the result that completes the last leg moves the milestone to SETTLED. A
 *       duplicate result changes nothing.</li>
 *   <li>refused: the same command is resent per the deposit-lock schedule (same eventId, D27); with no resend left
 *       the leg stays FAILED, the milestone SETTLING, and the error log alerts OPERATOR: the money is still locked.</li>
 * </ul>
 * A result that names no settlement leg of its milestone, or not the command that leg sent (user, entry type, fund,
 * amount), is refused (DLQ): a producer bug, not a retry case.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordSettlementLegResultUseCase {

    private final EscrowMilestoneRepository milestones;
    private final SettlementLegRepository settlementLegs;
    private final RetrySchedule retrySchedule;
    private final Clock clock;

    public void execute(ReturnResultCommand command) {
        if (command.milestoneId() == null) {
            throw new InvalidDepositLegException("Result " + command.eventId() + " for MILESTONE_PAYMENT names no"
                    + " milestone");
        }
        EscrowMilestone milestone = milestones.findByIdForUpdate(command.milestoneId())
                .orElseThrow(() -> new InvalidDepositLegException("Result " + command.eventId()
                        + " for unknown milestone " + command.milestoneId()));
        List<SettlementLeg> legs = settlementLegs.findByMilestoneIdForUpdate(command.milestoneId());
        SettlementLeg leg = legs.stream()
                .filter(l -> l.getRequestEventId().equals(command.sourceEventId()))
                .findFirst()
                .orElseThrow(() -> new InvalidDepositLegException("No settlement leg of milestone "
                        + command.milestoneId() + " was requested as " + command.sourceEventId()));
        if (!leg.getContractId().equals(command.contractId())
                || !leg.matchesResult(command.userId(), command.entryType(), command.fundType(),
                new Money(command.amount()))) {
            throw new InvalidDepositLegException("Result " + command.eventId() + " does not match settlement leg "
                    + leg.getLegId() + " (contract, user, entryType, fundType or amount)");
        }
        Instant now = clock.instant();

        if (command.returned()) {
            if (!leg.markCompleted(command.ledgerEntryId(), now)) {
                log.info("event=settlement_leg_result_duplicate milestoneId={} entryType={}", command.milestoneId(),
                        leg.getType());
                return;
            }
            settlementLegs.update(leg);
            log.info("event=settlement_leg_completed contractId={} milestoneId={} entryType={} amount={}"
                            + " ledgerEntryId={}", leg.getContractId(), leg.getMilestoneId(), leg.getType(),
                    leg.getAmount().amount().toPlainString(), command.ledgerEntryId());
            if (milestone.markSettledIfComplete(legs, now)) {
                milestones.update(milestone);
                log.info("event=milestone_settled contractId={} milestoneId={}", milestone.getContractId(),
                        milestone.getMilestoneId());
            }
            return;
        }

        SettlementLeg.FailureOutcome outcome = leg.markFailed(command.failureReason(), now, retrySchedule);
        switch (outcome) {
            case IGNORED_ALREADY_COMPLETED -> log.warn("event=settlement_leg_late_failure_ignored milestoneId={}"
                    + " entryType={} reason={}", command.milestoneId(), leg.getType(), command.failureReason());
            case IGNORED_NOT_IN_FLIGHT -> log.info("event=settlement_leg_failure_repeated milestoneId={}"
                    + " entryType={} eventId={}", command.milestoneId(), leg.getType(), command.eventId());
            case RETRY_SCHEDULED -> {
                settlementLegs.update(leg);
                log.warn("event=settlement_leg_refused milestoneId={} entryType={} failures={} nextAttemptAt={}"
                                + " reason={}", command.milestoneId(), leg.getType(), leg.getFailedAttempts(),
                        leg.getNextAttemptAt(), command.failureReason());
            }
            case RETRIES_EXHAUSTED -> {
                // TODO(#528): the stuck-leg scan (D68) must also cover settlement_leg REQUESTED, not only deposit legs.
                settlementLegs.update(leg);
                log.error("event=settlement_leg_failed_alert contractId={} milestoneId={} entryType={} amount={}"
                                + " sourceEventId={} reason={}: money still locked, milestone stays SETTLING,"
                                + " OPERATOR must act", leg.getContractId(), leg.getMilestoneId(), leg.getType(),
                        leg.getAmount().amount().toPlainString(), leg.getRequestEventId(), command.failureReason());
            }
        }
    }
}
