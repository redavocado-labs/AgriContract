package com.agricontract.escrow.application.usecase;

import com.agricontract.escrow.application.dto.ReturnResultCommand;
import com.agricontract.escrow.domain.exception.InvalidDepositLegException;
import com.agricontract.escrow.domain.model.Money;
import com.agricontract.escrow.domain.model.RemedyLeg;
import com.agricontract.escrow.domain.model.RetrySchedule;
import com.agricontract.escrow.domain.repository.ContractClearanceRepository;
import com.agricontract.escrow.domain.repository.RemedyLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * A bank result (release, refund or seize) that names a remedyLegId (#249), under the leg's row lock:
 * completed → COMPLETED, and the contract is queued for the clearance check (#609); refused → the same command is resent per the retry schedule (same eventId, D27), with no
 * resend left the leg stays FAILED and the error log alerts OPERATOR. A result for a leg escrow does not know is
 * logged and acked; one that does not match the command that leg sent is refused (DLQ).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordRemedyLegResultUseCase {

    private final RemedyLegRepository remedyLegs;
    private final ContractClearanceRepository clearances;
    private final RetrySchedule retrySchedule;
    private final Clock clock;

    public void execute(ReturnResultCommand command) {
        Optional<RemedyLeg> found = remedyLegs.findByIdForUpdate(command.remedyLegId());
        if (found.isEmpty()) {
            log.warn("event=remedy_leg_result_unknown eventId={} remedyLegId={} contractId={}", command.eventId(),
                    command.remedyLegId(), command.contractId());
            return;
        }
        RemedyLeg leg = found.get();
        if (!leg.getRequestEventId().equals(command.sourceEventId())
                || !leg.getRemedyDecisionId().equals(command.remedyDecisionId())
                || !leg.matchesResult(command.contractId(), command.userId(), command.entryType(),
                command.fundType(), new Money(command.amount()))) {
            throw new InvalidDepositLegException("Result " + command.eventId() + " does not match remedy leg "
                    + leg.getRemedyLegId() + " (decision, source event, contract, user, entryType, fundType or"
                    + " amount)");
        }
        Instant now = clock.instant();

        if (command.returned()) {
            if (!leg.markCompleted(command.ledgerEntryId(), now)) {
                log.info("event=remedy_leg_result_duplicate remedyLegId={}", leg.getRemedyLegId());
                return;
            }
            remedyLegs.update(leg);
            clearances.request(leg.getContractId(), now);
            log.info("event=remedy_leg_completed remedyDecisionId={} remedyLegId={} contractId={} entryType={}"
                            + " amount={} ledgerEntryId={}", leg.getRemedyDecisionId(), leg.getRemedyLegId(),
                    leg.getContractId(), leg.getEntryType(), leg.getAmount().amount().toPlainString(),
                    command.ledgerEntryId());
            return;
        }

        RemedyLeg.FailureOutcome outcome = leg.markFailed(command.failureReason(), now, retrySchedule);
        switch (outcome) {
            case IGNORED_ALREADY_COMPLETED -> log.warn("event=remedy_leg_late_failure_ignored remedyLegId={} reason={}",
                    leg.getRemedyLegId(), command.failureReason());
            case IGNORED_NOT_IN_FLIGHT -> log.info("event=remedy_leg_failure_repeated remedyLegId={} eventId={}",
                    leg.getRemedyLegId(), command.eventId());
            case RETRY_SCHEDULED -> {
                remedyLegs.update(leg);
                log.warn("event=remedy_leg_refused remedyLegId={} entryType={} failures={} nextAttemptAt={} reason={}",
                        leg.getRemedyLegId(), leg.getEntryType(), leg.getAttempts(), leg.getNextAttemptAt(),
                        command.failureReason());
            }
            case RETRIES_EXHAUSTED -> {
                remedyLegs.update(leg);
                log.error("event=remedy_leg_failed_alert remedyDecisionId={} remedyLegId={} contractId={}"
                                + " entryType={} amount={} sourceEventId={} reason={}: remedy not applied, OPERATOR"
                                + " must act", leg.getRemedyDecisionId(), leg.getRemedyLegId(), leg.getContractId(),
                        leg.getEntryType(), leg.getAmount().amount().toPlainString(), leg.getRequestEventId(),
                        command.failureReason());
            }
        }
    }
}
